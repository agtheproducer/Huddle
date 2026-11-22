package com.agentboard.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.js.Date
import kotlin.js.Promise

internal fun requireLogin() = when (js("process.env.AGENT_BOARD_REQUIRE_AUTH") as? String) {
    null, "false" -> false
    else -> true
}

private val clientId get() = js("process.env.BOB_GITHUB_CLIENT_ID") as? String
private val clientSecret get() = js("process.env.BOB_GITHUB_CLIENT_SECRET") as? String

internal fun authConfig() = JsonObject(mapOf(
    "client_id" to JsonPrimitive(clientId.orEmpty()),
    "browser" to JsonPrimitive(!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank())
)).toString()

/** Board-only credentials expire after eight hours and are invalidated by a restart. */
internal object Sessions {
    private data class Session(val login: String, val until: Double)
    private val active = mutableMapOf<String, Session>()
    private val crypto = js("require('node:crypto')")

    fun issue(login: String): String {
        val now = Date.now()
        active.entries.removeAll { it.value.until <= now }
        check(active.size < 4096)
        val token = "bob_" + crypto.randomBytes(32).toString("hex")
        active[token] = Session(login, now + 8 * 3600_000)
        return token
    }

    fun lookup(header: String?): String? {
        if (header == null || !header.startsWith("Bearer bob_")) return null
        val session = active[header.removePrefix("Bearer ")] ?: return null
        return session.login.takeIf { session.until > Date.now() }
    }
}

internal fun exchangeGithubToken(req: dynamic, res: dynamic) {
    res.setHeader("Cache-Control", "no-store")
    val header = req.headers["authorization"] as? String
    if (header == null || !header.matches(Regex("Bearer [A-Za-z0-9_]+"))) {
        sendError(res, 401, "A GitHub credential is required")
        return
    }
    issueForGithubUser(header, res)
}

private fun issueForGithubUser(header: String, res: dynamic) {
    val options = js("({redirect:'error',headers:{Accept:'application/json','User-Agent':'BOB-board'},signal:AbortSignal.timeout(8000)})")
    options.headers.Authorization = header
    val request = js("fetch")("https://api.github.com/user", options) as Promise<dynamic>
    request.then { response: dynamic ->
        if (response.status == 401) {
            sendError(res, 401, "GitHub declined the credential")
            null
        } else {
            check(response.ok == true)
            (response.json() as Promise<dynamic>).then { user: dynamic ->
                val login = user.login as? String
                check(!login.isNullOrBlank())
                send(res, 200, JsonObject(mapOf("login" to JsonPrimitive(login),
                    "session" to JsonPrimitive(Sessions.issue(login)))).toString())
            }
        }
    }.catch {
        sendError(res, 503, "GitHub sign-in is temporarily unavailable")
    }
}

internal fun exchangeBrowserCode(req: dynamic, res: dynamic) {
    res.setHeader("Cache-Control", "no-store")
    if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
        sendError(res, 503, "Browser sign-in is not configured")
        return
    }
    var bytes = 0
    val chunks = mutableListOf<dynamic>()
    req.on("data", { part: dynamic ->
        bytes += part.length as Int
        if (bytes <= 2048) chunks.add(part)
    })
    req.on("end", {
        if (bytes > 2048) sendError(res, 413, "Request too large")
        else {
            val raw = js("Buffer").concat(chunks.toTypedArray()).toString("utf8") as String
            val fields = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull()
            fun field(name: String) = (fields?.get(name) as? JsonPrimitive)?.content.orEmpty()
            val code = field("code")
            val verifier = field("code_verifier")
            if (!code.matches(Regex("[a-zA-Z0-9_-]{8,256}")) || !verifier.matches(Regex("[a-zA-Z0-9_-]{43,128}"))) {
                sendError(res, 400, "Invalid authorization response")
            } else exchangeCode(code, verifier, res)
        }
    })
}

private fun exchangeCode(code: String, verifier: String, res: dynamic) {
    val options = js("({method:'POST',redirect:'error',headers:{Accept:'application/json','Content-Type':'application/json'},signal:AbortSignal.timeout(8000)})")
    options.body = JsonObject(mapOf(
        "client_id" to JsonPrimitive(clientId),
        "client_secret" to JsonPrimitive(clientSecret),
        "redirect_uri" to JsonPrimitive("http://127.0.0.1:43829/bob/oauth/callback"),
        "code" to JsonPrimitive(code),
        "code_verifier" to JsonPrimitive(verifier)
    )).toString()
    val request = js("fetch")("https://github.com/login/oauth/access_token", options) as Promise<dynamic>
    request.then { response: dynamic ->
        check(response.ok == true)
        (response.json() as Promise<dynamic>).then { reply: dynamic ->
            val token = reply.access_token as? String
            if (token.isNullOrBlank()) sendError(res, 401, "Authorization expired or declined")
            else issueForGithubUser("Bearer $token", res)
        }
    }.catch { sendError(res, 503, "GitHub sign-in is temporarily unavailable") }
}
