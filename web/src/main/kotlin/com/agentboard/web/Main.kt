package com.agentboard.web

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.GitHubIssueStore
import com.agentboard.core.HttpBoardStore
import com.agentboard.core.InMemoryBoardStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private fun log(message: String) = System.err.println("[agent-board-web] $message")

fun main() {
    val env = System.getenv()
    val repo = env["AGENT_BOARD_REPO"]?.takeIf { it.isNotBlank() }
    val issue = env["AGENT_BOARD_ISSUE"]?.takeIf { it.isNotBlank() }?.toIntOrNull()
    val token = env["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() }
    val me = env["AGENT_BOARD_AGENT"]?.takeIf { it.isNotBlank() } ?: "amit-claude"
    val port = env["AGENT_BOARD_WEB_PORT"]?.toIntOrNull() ?: 8080
    val httpUrl = env["AGENT_BOARD_HTTP_URL"]?.takeIf { it.isNotBlank() }?.trimEnd('/')
    val live = httpUrl != null || (repo != null && issue != null)

    // With a token GitHub allows 5000 requests an hour, so a 2s poll is fine. Without
    // one it is 60 an hour, measured, including 304s — so roughly one a minute.
    // The HTTP board server has no rate limit, so it is polled every second.
    val pollMs = env["AGENT_BOARD_POLL_MS"]?.toLongOrNull()
        ?: if (httpUrl != null) 1_000L else if (token != null) 2_000L else 60_000L

    val store = when {
        httpUrl != null -> HttpBoardStore(httpUrl)
        live && token != null -> GitHubIssueStore(repo!!, issue!!, token)
        live -> PublicIssueStore(repo!!, issue!!)
        else -> InMemoryBoardStore(DemoFixtures.comments())
    }
    val client = BoardClient(store, me)
    val source = when {
        httpUrl != null -> "board server $httpUrl"
        live && token != null -> "$repo issue #$issue"
        live -> "$repo issue #$issue (public, read-only, no token)"
        else -> "demo fixtures (nothing is read from GitHub)"
    }

    val hub = Hub(client, me, source, live)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    // Cached, not fixed: every open browser tab holds one thread for its event stream.
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/") { ex ->
        when (ex.requestURI.path) {
            "/" -> serveResource(ex, "/index.html", "text/html; charset=utf-8")
            "/api/board" -> respond(ex, 200, "application/json; charset=utf-8", hub.snapshot())
            "/api/stream" -> hub.stream(ex)
            else -> respond(ex, 404, "text/plain; charset=utf-8", "Not found")
        }
    }
    server.start()

    // One poller for the whole server, however many tabs are open.
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "board-poller").apply { isDaemon = true } }
        .scheduleWithFixedDelay({ hub.refresh() }, 0, pollMs, TimeUnit.MILLISECONDS)

    log("reading $source every ${pollMs}ms, as $me")
    log("open http://localhost:$port")
}

/** Polls the board once for everyone and pushes each change straight to open browsers. */
private class Hub(
    private val client: BoardClient,
    private val me: String,
    private val source: String,
    private val live: Boolean,
) {
    private val listeners = Collections.synchronizedList(mutableListOf<OutputStream>())

    /** Declared before [payload]: rendering the first payload already reads it. */
    private var lastBoard: BoardState? = null

    @Volatile
    private var payload: String = render(null, "starting up")

    fun snapshot(): String = payload

    fun refresh() {
        val next = try {
            render(client.board(), null)
        } catch (e: Exception) {
            val reason = "${e::class.simpleName}: ${e.message}"
            log("fetch failed: $reason")
            // Keep the last good board on screen; only the error line changes.
            render(lastBoard, reason)
        }
        if (next == payload) return
        payload = next
        push(next)
    }

    private fun push(data: String) {
        val frame = "data: ${data.replace("\n", " ")}\n\n".toByteArray(StandardCharsets.UTF_8)
        synchronized(listeners) {
            val dead = mutableListOf<OutputStream>()
            for (out in listeners) {
                try {
                    out.write(frame)
                    out.flush()
                } catch (_: IOException) {
                    dead += out
                }
            }
            listeners.removeAll(dead)
        }
    }

    fun stream(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            out.write("retry: 2000\n\ndata: ${payload.replace("\n", " ")}\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (_: IOException) {
            return
        }
        listeners.add(out)
        // Hold the connection open. A comment line every 20s keeps proxies from closing it.
        try {
            while (true) {
                Thread.sleep(20_000)
                synchronized(listeners) { if (out !in listeners) return }
                out.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
            listeners.remove(out)
        }
    }

    private fun render(board: BoardState?, error: String?): String {
        if (board != null) lastBoard = board
        val b = board ?: lastBoard
        return buildJsonObject {
            put("source", source)
            put("live", live)
            put("me", me)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            put("last_comment_id", b?.lastCommentId ?: 0L)
            putJsonArray("agents") {
                b?.agents?.values?.sortedBy { it.agent }?.forEach { a ->
                    add(
                        buildJsonObject {
                            put("agent", a.agent)
                            put("branch", a.branch ?: "")
                            put("active_tasks", JsonArray(a.activeTasks.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
            putJsonArray("tasks") {
                b?.activeTasks?.sortedBy { it.taskId }?.forEach { t ->
                    add(
                        buildJsonObject {
                            put("task_id", t.taskId)
                            put("owner", t.owner ?: "")
                            put("branch", t.branch ?: "")
                            put("paths", JsonArray(t.paths.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
            putJsonArray("conflicts") {
                b?.openConflicts?.forEach { c ->
                    add(
                        buildJsonObject {
                            put("task_id", c.taskId ?: "")
                            put("claimant", c.claimant)
                            put("current_owner", c.currentOwner)
                            put("paths", JsonArray(c.overlappingPaths.map { JsonPrimitive(it) }))
                            put("reason", c.reason)
                        },
                    )
                }
            }
            putJsonArray("timeline") {
                b?.timeline?.takeLast(80)?.forEach { p ->
                    add(
                        buildJsonObject {
                            put("comment_id", p.commentId)
                            put("created_at", p.createdAt)
                            put("agent", p.event.agent)
                            // Who really posted it. The agent field is self-declared, so the view
                            // shows the GitHub account beside it.
                            put("github_author", p.author)
                            p.verifiedLogin?.let { put("verified_login", it) }
                            put("type", p.event.type)
                            put("task_id", p.event.taskId ?: "")
                            put("to_agent", p.event.toAgent ?: "")
                            put("branch", p.event.branch ?: "")
                            put("commit", p.event.commit ?: "")
                            put("message", p.event.message ?: p.event.summary ?: "")
                            put("paths", JsonArray(p.event.paths.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
        }.toString()
    }
}

private fun serveResource(ex: HttpExchange, path: String, contentType: String) {
    val bytes = object {}.javaClass.getResourceAsStream(path)?.readBytes()
    if (bytes == null) {
        respond(ex, 500, "text/plain; charset=utf-8", "Missing resource $path")
        return
    }
    ex.responseHeaders.add("Content-Type", contentType)
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun respond(ex: HttpExchange, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.add("Content-Type", contentType)
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
