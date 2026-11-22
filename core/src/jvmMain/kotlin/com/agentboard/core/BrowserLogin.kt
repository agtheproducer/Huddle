package com.agentboard.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.*

/** Each participant's browser returns to the loopback listener on their own laptop. */
object BrowserLogin {
    const val REDIRECT = "http://127.0.0.1:43829/bob/oauth/callback"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private fun nonce() = encoder.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    fun signIn(board: String, client: String, open: (String) -> Unit, checkCanceled: () -> Unit): JsonObject {
        val origin = BoardSignIn.endpoint(board)
        val state = nonce()
        val verifier = nonce()
        val challenge = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val redirect = URI(REDIRECT)
        val result = CompletableFuture<String>()
        val listener = try {
            HttpServer.create(InetSocketAddress(redirect.host, redirect.port), 4)
        } catch (e: java.net.BindException) {
            throw IllegalStateException("Another sign-in is using port ${redirect.port}. Finish it and try again.", e)
        }
        listener.createContext(redirect.path) { req ->
            req.use {
                val params = req.requestURI.rawQuery.orEmpty().split('&').associate {
                    it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }.getOrDefault("")
                }
                val valid = req.requestMethod == "GET" && req.requestURI.path == redirect.path &&
                    req.requestHeaders.getFirst("Host") == redirect.authority && params["state"] == state
                val code = params["code"]
                val accepted = valid && !code.isNullOrBlank() && params["error"] == null
                val message = when {
                    accepted -> "GitHub returned your authorization. Return to IntelliJ to see your BOB account."
                    valid -> "Sign-in was cancelled. You can return to IntelliJ."
                    else -> "This callback does not match the current BOB sign-in."
                }.toByteArray()
                try {
                    req.responseHeaders.set("Cache-Control", "no-store")
                    req.responseHeaders.set("Referrer-Policy", "no-referrer")
                    req.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                    req.sendResponseHeaders(if (valid) 200 else 400, message.size.toLong())
                    req.responseBody.write(message)
                } finally {
                    if (accepted) result.complete(code)
                    else if (valid) result.completeExceptionally(IllegalStateException("GitHub sign-in was cancelled."))
                }
            }
        }
        listener.start()
        try {
            open("https://github.com/login/oauth/authorize?client_id=${encode(client)}&redirect_uri=${encode(REDIRECT)}" +
                "&scope=&state=$state&code_challenge=$challenge&code_challenge_method=S256")
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (System.nanoTime() < deadline) {
                checkCanceled()
                val code = try { result.get(250, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { continue }
                val body = buildJsonObject { put("code", code); put("code_verifier", verifier) }
                return Json.parseToJsonElement(BoardSignIn.request("$origin/api/auth/browser", body.toString())).jsonObject
            }
            error("GitHub sign-in timed out. Click sign in again.")
        } finally {
            listener.stop(0)
        }
    }
}
