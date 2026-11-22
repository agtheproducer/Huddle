package com.agentboard.server

import com.agentboard.core.InMemoryBoardStore
import com.agentboard.core.RawComment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The board's state: the append-only event log, its file on disk, and the SSE listeners.
 *
 * Events live in core's [InMemoryBoardStore], seeded from [file] at startup, so ids keep
 * increasing across restarts. Each accepted event is written back to [file] before
 * listeners hear about it. With no file (tests) the board is memory-only.
 */
class Board(private val file: EventFile?) {
    private val store = InMemoryBoardStore(file?.load().orEmpty())
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(RawComment.serializer())

    // SSE write callbacks — each connected stream registers one to receive new events.
    private val sseCallbacks = mutableListOf<(String) -> Unit>()

    /** Events with id greater than [since], oldest first. */
    fun events(since: Long = 0): List<RawComment> = store.fetchComments().filter { it.id > since }

    /** [events] as a JSON array string. */
    fun eventsJson(since: Long = 0): String = json.encodeToString(listSerializer, events(since))

    /**
     * Appends a comment body that already passed [EventRules.rejection], saves the log
     * and broadcasts it. Returns the created comment as JSON.
     */
    fun post(body: String): String {
        val comment = store.postComment(body)
        try {
            file?.save(store.fetchComments())
        } catch (e: Throwable) {
            // Keep serving: the event is on the live board, it just is not durable.
            console.error("Could not save board to ${file?.path}: ${e.message}")
        }
        val eventJson = json.encodeToString(RawComment.serializer(), comment)
        for (cb in sseCallbacks.toList()) {
            try {
                cb("data: $eventJson\n\n")
            } catch (e: Exception) {
                // Listener disconnected, ignore
            }
        }
        return eventJson
    }

    /**
     * Registers an SSE callback and returns the existing events as SSE data lines.
     * The caller sends those first; the callback then receives every new event.
     */
    fun registerSseCallback(writeCallback: (String) -> Unit): List<String> {
        sseCallbacks.add(writeCallback)
        return events().map { "data: ${json.encodeToString(RawComment.serializer(), it)}\n\n" }
    }

    fun unregisterSseCallback(writeCallback: (String) -> Unit) {
        sseCallbacks.remove(writeCallback)
    }
}

/** Where the board file lives: env `AGENT_BOARD_DATA`, else `server/data/board-events.json`. */
fun dataPath(): String =
    (js("process.env.AGENT_BOARD_DATA") as? String)?.takeIf { it.isNotBlank() }
        ?: "server/data/board-events.json"

/** The process-wide board, loaded from disk on first use. */
val Server: Board by lazy {
    val file = EventFile(dataPath())
    Board(file).also { console.log("Board data: ${file.path} (${it.events().size} events loaded)") }
}
