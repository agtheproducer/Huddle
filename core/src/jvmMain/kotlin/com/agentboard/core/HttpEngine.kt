package com.agentboard.core

import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal actual object HttpEngine {
    /**
     * Timeouts so a server that hangs fails fast instead of blocking a background thread forever
     * (the IDE would otherwise stop refreshing without saying why).
     * The default proxy selector is the IDE's own inside IntelliJ, so its proxy settings apply.
     */
    internal val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    internal val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply { ProxySelector.getDefault()?.let(::proxy) }
        .build()

    actual fun get(url: String): String = send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET(),
        "GET", url,
    )

    actual fun post(url: String, body: String): String = send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)),
        "POST", url,
    )

    private fun send(builder: HttpRequest.Builder, method: String, url: String): String {
        val response = try {
            http.send(builder.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IllegalStateException("$method $url timed out after ${REQUEST_TIMEOUT.seconds}s", e)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("$method $url: cannot connect (${e.message ?: "refused"})", e)
        }
        check(response.statusCode() in 200..299) { "$method $url returned ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }
}
