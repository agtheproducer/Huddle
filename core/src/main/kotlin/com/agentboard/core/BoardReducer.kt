package com.agentboard.core

/**
 * Rebuilds the board from the event log, in GitHub comment order.
 *
 * Claim rule (docs/protocol.md): the first valid claim on a task or path owns it.
 * A later overlapping claim by another agent is rejected and recorded as a conflict.
 * This is a coordination warning, not a file lock.
 */
object BoardReducer {

    fun reduce(events: List<PostedEvent>): BoardState =
        events.sortedBy { it.commentId }.fold(BoardState.EMPTY, ::apply)

    fun apply(state: BoardState, posted: PostedEvent): BoardState {
        val withPresence = touchAgent(state, posted)
        val e = posted.event
        return when (e.type) {
            EventType.TASK_CLAIMED -> claim(withPresence, posted)
            EventType.HANDOFF -> handoff(withPresence, posted)
            EventType.TASK_RELEASED -> finish(withPresence, posted, TaskStatus.RELEASED)
            EventType.TASK_DONE -> finish(withPresence, posted, TaskStatus.DONE)
            EventType.PROGRESS -> progress(withPresence, posted)
            EventType.MESSAGE -> withPresence.copy(timeline = withPresence.timeline + posted)
            EventType.CONFLICT -> withPresence.copy(
                timeline = withPresence.timeline + posted,
                conflicts = withPresence.conflicts + Conflict(
                    commentId = posted.commentId,
                    taskId = e.taskId,
                    claimant = e.agent,
                    currentOwner = e.toAgent ?: "unknown",
                    overlappingPaths = e.paths,
                    reason = e.message ?: "Conflict reported by ${e.agent}",
                ),
            )
            else -> withPresence
        }.copy(lastCommentId = posted.commentId)
    }

    private fun claim(state: BoardState, posted: PostedEvent): BoardState {
        val e = posted.event
        val taskId = e.taskId ?: return reject(state, posted, "task_claimed needs task_id")

        val existing = state.tasks[taskId]
        if (existing != null && existing.isActive && existing.owner != e.agent) {
            return conflict(state, posted, existing.owner!!, e.paths, "Task '$taskId' is already owned by ${existing.owner}")
        }

        val clashes = state.activeTasks
            .filter { it.owner != e.agent && it.taskId != taskId }
            .mapNotNull { task ->
                val overlap = e.paths.filter { it in task.paths }
                if (overlap.isEmpty()) null else task to overlap
            }
        if (clashes.isNotEmpty()) {
            val (task, overlap) = clashes.first()
            return conflict(state, posted, task.owner!!, overlap, "${overlap.joinToString()} already claimed by ${task.owner} for task '${task.taskId}'")
        }

        val task = TaskState(
            taskId = taskId,
            owner = e.agent,
            paths = e.paths,
            branch = e.branch ?: existing?.branch,
            status = TaskStatus.CLAIMED,
            lastMessage = e.message,
            claimedAtCommentId = posted.commentId,
        )
        return state.copy(
            tasks = state.tasks + (taskId to task),
            timeline = state.timeline + posted,
        ).refreshAgents()
    }

    private fun handoff(state: BoardState, posted: PostedEvent): BoardState {
        val e = posted.event
        val taskId = e.taskId ?: return reject(state, posted, "handoff needs task_id")
        val task = state.tasks[taskId]
        if (task == null || !task.isActive || task.owner != e.agent) {
            return reject(state, posted, "Only the current owner can hand off '$taskId'")
        }
        val to = e.toAgent ?: return reject(state, posted, "handoff needs to_agent")
        if (e.commit.isNullOrBlank() || e.branch.isNullOrBlank()) {
            return reject(state, posted, "handoff needs a pushed branch and commit")
        }
        val handed = task.copy(
            owner = to,
            branch = e.branch,
            paths = e.paths.ifEmpty { task.paths },
            lastMessage = e.summary ?: e.message,
        )
        return state.copy(
            tasks = state.tasks + (taskId to handed),
            timeline = state.timeline + posted,
            conflicts = resolveConflicts(state.conflicts, task),
        ).refreshAgents()
    }

    private fun finish(state: BoardState, posted: PostedEvent, status: TaskStatus): BoardState {
        val e = posted.event
        val taskId = e.taskId ?: return reject(state, posted, "${e.type} needs task_id")
        val task = state.tasks[taskId]
        if (task == null || !task.isActive || task.owner != e.agent) {
            return reject(state, posted, "Only the current owner can mark '$taskId' as ${status.name.lowercase()}")
        }
        return state.copy(
            tasks = state.tasks + (taskId to task.copy(status = status, owner = if (status == TaskStatus.RELEASED) null else task.owner, lastMessage = e.message ?: task.lastMessage)),
            timeline = state.timeline + posted,
            conflicts = resolveConflicts(state.conflicts, task),
        ).refreshAgents()
    }

    private fun progress(state: BoardState, posted: PostedEvent): BoardState {
        val e = posted.event
        val task = e.taskId?.let { state.tasks[it] }
        val tasks = if (task != null && task.owner == e.agent) {
            state.tasks + (task.taskId to task.copy(lastMessage = e.message ?: task.lastMessage))
        } else state.tasks
        return state.copy(tasks = tasks, timeline = state.timeline + posted)
    }

    private fun conflict(state: BoardState, posted: PostedEvent, owner: String, paths: List<String>, reason: String) =
        state.copy(
            conflicts = state.conflicts + Conflict(posted.commentId, posted.event.taskId, posted.event.agent, owner, paths, reason),
            timeline = state.timeline + posted,
            rejected = state.rejected + RejectedEvent(posted, reason),
        )

    private fun reject(state: BoardState, posted: PostedEvent, reason: String) =
        state.copy(rejected = state.rejected + RejectedEvent(posted, reason))

    /** Conflicts about a task (or its paths) are resolved once that task changes hands or ends. */
    private fun resolveConflicts(conflicts: List<Conflict>, task: TaskState): List<Conflict> =
        conflicts.map { c ->
            val related = c.taskId == task.taskId || c.overlappingPaths.any { it in task.paths }
            if (related) c.copy(resolved = true) else c
        }

    private fun touchAgent(state: BoardState, posted: PostedEvent): BoardState {
        val name = posted.event.agent
        val current = state.agents[name]
        val updated = (current ?: AgentState(name, emptyList(), null, posted.createdAt, posted.commentId))
            .copy(lastSeen = posted.createdAt, lastCommentId = posted.commentId)
        return state.copy(agents = state.agents + (name to updated)).refreshAgents()
    }

    /** Recomputes each agent's active tasks and branch from the task table. */
    private fun BoardState.refreshAgents(): BoardState {
        val known = agents.keys + tasks.values.mapNotNull { it.owner }
        val refreshed = known.associateWith { name ->
            val owned = activeTasks.filter { it.owner == name }
            val base = agents[name] ?: AgentState(name, emptyList(), null, "", 0)
            base.copy(activeTasks = owned.map { it.taskId }, branch = owned.lastOrNull()?.branch ?: base.branch)
        }
        return copy(agents = refreshed)
    }
}
