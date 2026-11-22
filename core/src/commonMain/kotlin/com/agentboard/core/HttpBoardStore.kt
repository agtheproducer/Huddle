package com.agentboard.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * BoardStore that connects to the Agent Board HTTP server instead of GitHub.
 *
 * The server exposes:
 * - `GET /api/events[?since=<id>]` — the events as a JSON array (only ids > since, if given)
 * - `POST /api/events` — accepts `{"body": "<event>"}`, returns the created comment
 *
 * Reads are incremental: the store keeps the events it has seen and asks only for newer ones, so a
 * client polling every few seconds downloads what changed, not the whole log. Every [fullEvery]-th
 * read is a full one, which notices a board that was wiped (ids start again from 1) within a minute.
 *
 * @param baseUrl The base URL of the HTTP server, e.g. `https://crawling-reflector-nail.ngrok-free.dev`
 */
class HttpBoardStore(
    private val baseUrl: String,
    private val fullEvery: Int = 12,
    /** GET and POST, replaceable in tests. */
    private val get: (String) -> String = HttpEngine::get,
    private val post: (String, String) -> String = HttpEngine::post,
) : BoardStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val rawCommentListSerializer = ListSerializer(RawComment.serializer())

    @Serializable
    private data class PostRequest(val body: String)

    private var seen: List<RawComment> = emptyList()
    private var reads = 0

    override fun fetchComments(): List<RawComment> = synchronizedRead()

    // Board reads come from background threads (IDE polling, MCP tool calls); one at a time keeps the cache whole.
    private fun synchronizedRead(): List<RawComment> = lock.withLockCompat {
        val n = reads++
        val full = seen.isEmpty() || n % fullEvery == 0
        val lastId = seen.maxOfOrNull { it.id } ?: 0
        val fresh = decode(get(if (full) "$baseUrl/api/events" else "$baseUrl/api/events?since=$lastId"))
        seen = if (full) fresh else seen + fresh.filter { it.id > lastId }
        seen
    }

    override fun postComment(body: String): RawComment {
        val payload = json.encodeToString(PostRequest.serializer(), PostRequest(body))
        val response = post("$baseUrl/api/events", payload)
        return json.decodeFromString(RawComment.serializer(), response)
    }

    // A reply that is not the event list (a proxy error page, a wrong URL) is an error, not an empty
    // board: showing "no agents" would hide the problem from the people watching it.
    private fun decode(body: String): List<RawComment> = try {
        json.decodeFromString(rawCommentListSerializer, body)
    } catch (e: Exception) {
        throw IllegalStateException("$baseUrl did not return board events: ${body.take(120)}", e)
    }

    private val lock = Any()
}

/** `synchronized` exists on the JVM only; on other targets reads are single-threaded anyway. */
internal expect inline fun <T> Any.withLockCompat(block: () -> T): T
