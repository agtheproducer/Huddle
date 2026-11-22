package com.agentboard.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * BoardStore that connects to an HTTP server instead of GitHub.
 *
 * Uses [HttpEngine] for platform-agnostic HTTP calls (fetch on JS, HttpClient on JVM).
 * The server must expose:
 * - `GET /api/events` — returns all comments as JSON array
 * - `POST /api/events` — accepts a JSON object with `body` field, returns the created comment
 *
 * @param baseUrl The base URL of the HTTP server, e.g. `https://agent-board.vercel.app`
 */
class HttpBoardStore(
    private val baseUrl: String,
) : BoardStore {
    
    private val json = Json { ignoreUnknownKeys = true }
    private val rawCommentListSerializer = ListSerializer(RawComment.serializer())

    @Serializable
    private data class PostRequest(val body: String)

    override fun fetchComments(): List<RawComment> {
        val body = HttpEngine.get("$baseUrl/api/events")
        // A reply that is not the event list (a proxy error page, a wrong URL) is an error, not an empty
        // board: showing "no agents" would hide the problem from the people watching it.
        return try {
            json.decodeFromString(rawCommentListSerializer, body)
        } catch (e: Exception) {
            throw IllegalStateException("$baseUrl did not return board events: ${body.take(120)}", e)
        }
    }

    override fun postComment(body: String): RawComment {
        val payload = json.encodeToString(PostRequest.serializer(), PostRequest(body))
        val response = HttpEngine.post("$baseUrl/api/events", payload)
        return json.decodeFromString(RawComment.serializer(), response)
    }
}
