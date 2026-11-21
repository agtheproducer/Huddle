package com.agentboard.server

import com.agentboard.core.InMemoryBoardStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * In-memory event store with SSE (Server-Sent Events) broadcasting.
 *
 * Holds all board events in memory and notifies connected SSE clients
 * when new events arrive. This is the shared state for the Vercel
 * Fluid Compute function — it persists across requests within the
 * same function instance.
 */
object Server {
    private val store = InMemoryBoardStore()
    private val json = Json { ignoreUnknownKeys = true }

    // SSE write callbacks — each listener registers a callback to receive events
    private val sseCallbacks = mutableListOf<(String) -> Unit>()

    /** Get all events as a JSON array string. */
    fun getEvents(): String {
        val comments = store.fetchComments()
        return json.encodeToString(comments)
    }

    /** Post a new event body and return the created RawComment as JSON. */
    fun postEvent(body: String): String {
        val comment = store.postComment(body)
        val eventJson = json.encodeToString(comment)
        // Broadcast to all SSE listeners (copy list to avoid concurrent modification)
        val callbacks = sseCallbacks.toList()
        for (cb in callbacks) {
            try {
                cb("data: $eventJson\n\n")
            } catch (e: Exception) {
                // Listener disconnected, ignore
            }
        }
        return eventJson
    }

    /**
     * Register an SSE callback. Returns a list of existing events as SSE data strings.
     * The caller should send the initial events first, then the callback handles new events.
     */
    fun registerSseCallback(writeCallback: (String) -> Unit): List<String> {
        sseCallbacks.add(writeCallback)
        // Return initial events as SSE data lines
        val comments = store.fetchComments()
        return comments.map { comment ->
            val eventJson = json.encodeToString(comment)
            "data: $eventJson\n\n"
        }
    }

    /** Unregister an SSE callback. */
    fun unregisterSseCallback(writeCallback: (String) -> Unit) {
        sseCallbacks.remove(writeCallback)
    }
}
