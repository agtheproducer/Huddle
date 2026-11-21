package com.agentboard.web

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.GitHubIssueStore
import com.agentboard.core.InMemoryBoardStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private val LIVE_VARS = listOf("AGENT_BOARD_REPO", "AGENT_BOARD_ISSUE", "GITHUB_TOKEN")

/** How long a fetched board is reused, so many browser tabs cannot exhaust the API rate limit. */
private const val CACHE_MS = 3_000L

private fun log(message: String) = System.err.println("[agent-board-web] $message")

fun main() {
    val env = System.getenv()
    val live = LIVE_VARS.all { !env[it].isNullOrBlank() }
    val agent = env["AGENT_BOARD_AGENT"]?.takeIf { it.isNotBlank() } ?: "web-viewer"
    val port = env["AGENT_BOARD_WEB_PORT"]?.toIntOrNull() ?: 8080

    val store = if (live) GitHubIssueStore.fromEnvironment(env) else InMemoryBoardStore(DemoFixtures.comments())
    val client = BoardClient(store, agent)

    val source =
        if (live) "${env["AGENT_BOARD_REPO"]} issue #${env["AGENT_BOARD_ISSUE"]}"
        else "demo fixtures (nothing is read from GitHub)"

    val cache = BoardCache(client)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/") { ex ->
        when (ex.requestURI.path) {
            "/" -> serveResource(ex, "/index.html", "text/html; charset=utf-8")
            "/api/board" -> serveBoard(ex, cache, source, live)
            else -> respond(ex, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    server.start()
    log("reading $source")
    log("open http://localhost:$port")
}

/** Caches the board briefly. Serves the last good one if GitHub fails, with the error attached. */
private class BoardCache(private val client: BoardClient) {
    private var at = 0L
    private var board: BoardState? = null
    private var error: String? = null

    @Synchronized
    fun get(): Pair<BoardState?, String?> {
        val now = System.currentTimeMillis()
        if (board != null && now - at < CACHE_MS) return board to error
        try {
            board = client.board()
            error = null
        } catch (e: Exception) {
            error = "${e::class.simpleName}: ${e.message}"
            log("fetch failed: $error")
        }
        at = now
        return board to error
    }
}

private fun serveBoard(ex: HttpExchange, cache: BoardCache, source: String, live: Boolean) {
    val (board, error) = cache.get()
    val json = buildJsonObject {
        put("source", source)
        put("live", live)
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("last_comment_id", board?.lastCommentId ?: 0L)
        putJsonArray("agents") {
            board?.agents?.values?.sortedBy { it.agent }?.forEach { a ->
                add(
                    buildJsonObject {
                        put("agent", a.agent)
                        put("branch", a.branch ?: "")
                        put("last_seen", a.lastSeen)
                        put("active_tasks", JsonArray(a.activeTasks.map { JsonPrimitive(it) }))
                    },
                )
            }
        }
        putJsonArray("tasks") {
            board?.activeTasks?.sortedBy { it.taskId }?.forEach { t ->
                add(
                    buildJsonObject {
                        put("task_id", t.taskId)
                        put("owner", t.owner ?: "")
                        put("branch", t.branch ?: "")
                        put("paths", JsonArray(t.paths.map { JsonPrimitive(it) }))
                        put("last_message", t.lastMessage ?: "")
                    },
                )
            }
        }
        putJsonArray("conflicts") {
            board?.openConflicts?.forEach { c ->
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
            board?.timeline?.takeLast(60)?.forEach { p ->
                add(
                    buildJsonObject {
                        put("comment_id", p.commentId)
                        put("created_at", p.createdAt)
                        put("github_author", p.author)
                        put("agent", p.event.agent)
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
    }
    respond(ex, if (error != null && board == null) 502 else 200, "application/json; charset=utf-8", json.toString())
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
