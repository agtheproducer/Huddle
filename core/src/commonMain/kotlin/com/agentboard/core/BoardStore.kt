package com.agentboard.core

/** Where coordination events live. The prototype uses one GitHub Issue; tests use memory. */
interface BoardStore {
    fun fetchComments(): List<RawComment>
    fun postComment(body: String): RawComment
}

/**
 * The current time as an ISO-8601 string.
 *
 * The only thing in the board engine that needs the platform. Everything else —
 * the event format, the reducer, conflict detection, path overlap — is plain
 * Kotlin and compiles unchanged for every target.
 */
internal expect fun nowIso(): String

/** In-memory store for tests, fixtures and offline UI work. */
class InMemoryBoardStore(initial: List<RawComment> = emptyList()) : BoardStore {
    private val comments = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun fetchComments(): List<RawComment> = comments.toList()

    override fun postComment(body: String): RawComment {
        val c = RawComment(nextId++, nowIso(), "local", body)
        comments += c
        return c
    }
}
