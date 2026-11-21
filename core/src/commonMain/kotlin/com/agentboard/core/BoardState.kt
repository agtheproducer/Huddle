package com.agentboard.core

enum class TaskStatus { CLAIMED, RELEASED, DONE }

data class TaskState(
    val taskId: String,
    val owner: String?,
    val paths: List<String>,
    val branch: String?,
    val status: TaskStatus,
    val lastMessage: String?,
    val claimedAtCommentId: Long,
) {
    val isActive: Boolean get() = status == TaskStatus.CLAIMED && owner != null
}

data class AgentState(
    val agent: String,
    val activeTasks: List<String>,
    val branch: String?,
    val lastSeen: String,
    val lastCommentId: Long,
)

/** A rejected claim: someone else already owned the task or one of the paths. */
data class Conflict(
    val commentId: Long,
    val taskId: String?,
    val claimant: String,
    val currentOwner: String,
    val overlappingPaths: List<String>,
    val reason: String,
    val resolved: Boolean = false,
)

/** An event the reducer refused, with the reason, so the UI can explain it. */
data class RejectedEvent(val posted: PostedEvent, val reason: String)

/** The whole board, rebuilt from the event log. */
data class BoardState(
    val tasks: Map<String, TaskState>,
    val agents: Map<String, AgentState>,
    val conflicts: List<Conflict>,
    val timeline: List<PostedEvent>,
    val rejected: List<RejectedEvent>,
    val lastCommentId: Long,
) {
    val openConflicts: List<Conflict> get() = conflicts.filter { !it.resolved }
    val activeTasks: List<TaskState> get() = tasks.values.filter { it.isActive }

    /** Who currently owns [path], including claims on a containing directory. */
    fun ownerOfPath(path: String): TaskState? = activeTasks.firstOrNull { task ->
        task.paths.any { PathOverlap.overlaps(path, it) }
    }

    companion object {
        val EMPTY = BoardState(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), 0)
    }
}
