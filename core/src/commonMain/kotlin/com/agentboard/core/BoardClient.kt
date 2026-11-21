package com.agentboard.core

/** Result of a claim after re-reading the log: either we own it, or there is a conflict. */
data class ClaimResult(val owned: Boolean, val task: TaskState?, val conflict: Conflict?)

/**
 * The API that the MCP server (Agent B) and the IntelliJ tool window (Agent C) call.
 * One instance per agent identity, e.g. "amit-claude".
 */
class BoardClient(private val store: BoardStore, val agent: String) {

    fun board(): BoardState = BoardReducer.reduce(EventCodec.parseAll(store.fetchComments()))

    private fun post(event: BoardEvent): PostedEvent {
        val comment = store.postComment(EventCodec.toCommentBody(event))
        return EventCodec.parse(comment) ?: error("Posted event could not be parsed back")
    }

    /** Publishes a claim, then re-reads the log so concurrent claims resolve the same way for everyone. */
    fun claimTask(taskId: String, paths: List<String>, branch: String, message: String? = null): ClaimResult {
        val posted = post(BoardEvent(type = EventType.TASK_CLAIMED, agent = agent, taskId = taskId, branch = branch, paths = paths, message = message))
        val state = board()
        val task = state.tasks[taskId]
        val owned = task != null && task.isActive && task.owner == agent && task.claimedAtCommentId <= posted.commentId
        val conflict = state.conflicts.lastOrNull { it.commentId == posted.commentId }
        return ClaimResult(owned && conflict == null, task, conflict)
    }

    fun sendMessage(message: String, toAgent: String? = null, taskId: String? = null): PostedEvent =
        post(BoardEvent(type = EventType.MESSAGE, agent = agent, toAgent = toAgent, taskId = taskId, message = message))

    fun reportProgress(taskId: String, message: String): PostedEvent =
        post(BoardEvent(type = EventType.PROGRESS, agent = agent, taskId = taskId, message = message))

    /** Messages and handoffs addressed to [forAgent] (or to everyone) after [sinceCommentId]. */
    fun messages(forAgent: String = agent, sinceCommentId: Long = 0): List<PostedEvent> =
        board().timeline.filter { p ->
            p.commentId > sinceCommentId &&
                p.event.agent != forAgent &&
                (p.event.toAgent == null || p.event.toAgent == forAgent) &&
                p.event.type in setOf(EventType.MESSAGE, EventType.HANDOFF, EventType.CONFLICT)
        }

    /** Hands a task over. Requires a pushed branch and commit: a chat summary cannot move uncommitted code. */
    fun handoffTask(taskId: String, toAgent: String, summary: String, branch: String, commit: String): PostedEvent =
        post(BoardEvent(type = EventType.HANDOFF, agent = agent, taskId = taskId, toAgent = toAgent, summary = summary, branch = branch, commit = commit))

    fun releaseTask(taskId: String, message: String? = null): PostedEvent =
        post(BoardEvent(type = EventType.TASK_RELEASED, agent = agent, taskId = taskId, message = message))

    fun markDone(taskId: String, message: String? = null): PostedEvent =
        post(BoardEvent(type = EventType.TASK_DONE, agent = agent, taskId = taskId, message = message))
}
