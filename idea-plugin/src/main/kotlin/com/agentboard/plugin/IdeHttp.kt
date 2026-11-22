package com.agentboard.plugin

import com.intellij.util.io.HttpRequests

/**
 * HTTP for the board through IntelliJ's own stack ([HttpRequests]) instead of a plain JDK client, so it
 * follows the IDE's proxy settings (Settings → HTTP Proxy) and trusted certificates (Settings → Server
 * Certificates): a board behind a corporate proxy or a self-signed certificate works like any other IDE
 * connection. Same timeouts as the core client. Call from a background thread.
 */
object IdeHttp {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    fun get(url: String): String = request("GET", url) {
        HttpRequests.request(url)
            .accept("application/json")
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .readString()
    }

    fun post(url: String, body: String): String = request("POST", url) {
        HttpRequests.post(url, "application/json")
            .accept("application/json")
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .connect { it.write(body); it.readString() }
    }

    /** Turns the platform's exceptions into one readable line for the sidebar's status. */
    private inline fun request(method: String, url: String, block: () -> String): String = try {
        block()
    } catch (e: HttpRequests.HttpStatusException) {
        throw IllegalStateException("$method $url returned ${e.statusCode}: ${e.message?.take(200)}", e)
    } catch (e: java.net.SocketTimeoutException) {
        throw IllegalStateException("$method $url timed out", e)
    } catch (e: java.io.IOException) {
        throw IllegalStateException("$method $url: ${e.message ?: e.javaClass.simpleName}", e)
    }
}
