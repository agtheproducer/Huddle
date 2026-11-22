package com.agentboard.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Event types agreed in docs/protocol.md. Unknown types are ignored by the reducer. */
object EventType {
    const val TASK_CLAIMED = "task_claimed"
    const val MESSAGE = "message"
    const val PROGRESS = "progress"
    const val CONFLICT = "conflict"
    const val HANDOFF = "handoff"
    const val TASK_RELEASED = "task_released"
    const val TASK_DONE = "task_done"

    val ALL = setOf(TASK_CLAIMED, MESSAGE, PROGRESS, CONFLICT, HANDOFF, TASK_RELEASED, TASK_DONE)
}

/**
 * One coordination event, stored as JSON in a GitHub Issue comment.
 * Field names match the wire format in docs/protocol.md (snake_case).
 */
@Serializable
data class BoardEvent(
    val v: Int = 1,
    val type: String,
    val agent: String,
    @SerialName("task_id") val taskId: String? = null,
    val branch: String? = null,
    val paths: List<String> = emptyList(),
    val message: String? = null,
    @SerialName("to_agent") val toAgent: String? = null,
    val summary: String? = null,
    val commit: String? = null,
)

/** A raw GitHub Issue comment, before parsing. */
@Serializable
data class RawComment(
    val id: Long,
    val createdAt: String,
    val author: String,
    val body: String,
    val verifiedLogin: String? = null,
)

/**
 * A parsed event plus the ordering data GitHub gave us.
 * Ordering uses [commentId], never the agent's clock.
 */
data class PostedEvent(
    val commentId: Long,
    val createdAt: String,
    val author: String,
    val event: BoardEvent,
    val verifiedLogin: String? = null,
)
