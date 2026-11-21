package com.agentboard.core

import kotlinx.serialization.json.Json

/** Turns events into comment bodies and back. Malformed comments are skipped, never thrown. */
object EventCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
    }

    private val fenced = Regex("```(?:json)?\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

    /** The comment body an agent posts: the event as JSON in a fenced block. */
    fun toCommentBody(event: BoardEvent): String =
        "```json\n" + json.encodeToString(BoardEvent.serializer(), event) + "\n```"

    /** Parses one comment, or returns null if it holds no valid event. */
    fun parse(comment: RawComment): PostedEvent? {
        val candidate = fenced.find(comment.body)?.groupValues?.get(1)?.trim()
            ?: comment.body.trim()
        if (!candidate.startsWith("{")) return null
        val event = runCatching { json.decodeFromString(BoardEvent.serializer(), candidate) }.getOrNull()
            ?: return null
        if (event.type !in EventType.ALL || event.agent.isBlank()) return null
        return PostedEvent(comment.id, comment.createdAt, comment.author, event)
    }

    /** Parses all comments in GitHub's order (by comment id), skipping anything malformed. */
    fun parseAll(comments: List<RawComment>): List<PostedEvent> =
        comments.sortedBy { it.id }.mapNotNull(::parse)
}
