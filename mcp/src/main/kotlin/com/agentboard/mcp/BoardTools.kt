package com.agentboard.mcp

import com.agentboard.core.BoardClient
import com.agentboard.core.Conflict
import com.agentboard.core.PostedEvent
import com.agentboard.core.TaskState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Thrown for bad tool arguments; turned into {"error": ...} for the agent. */
class ToolInputException(message: String) : RuntimeException(message)

/**
 * The six board tools from docs/protocol.md, as plain functions over [BoardClient].
 * They take the tool's JSON arguments and return short JSON. [McpServer] registers them.
 */
class BoardTools(
    private val client: BoardClient,
    private val mode: String,
    /** How long one waiting call may block before returning "nothing yet". MCP clients often time out at 60 s. */
    private val maxWaitMillis: Long = 45_000,
    private val pollMillis: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    val agent: String get() = client.agent

    /**
     * Last comment id already handed to this agent by a message tool, so waiting only returns
     * what is new. Set from the board on first use, so old history is not replayed.
     */
    private var cursor: Long? = null

    private fun cursorOrNow(): Long = cursor ?: client.board().lastCommentId.also { cursor = it }

    fun getBoard(): JsonObject {
        val board = client.board()
        return buildJsonObject {
            put("mode", mode)
            put("you", client.agent)
            putJsonArray("tasks") {
                board.tasks.values.sortedBy { it.claimedAtCommentId }.forEach { add(taskJson(it)) }
            }
            putJsonArray("open_conflicts") { board.openConflicts.forEach { add(conflictJson(it)) } }
            putJsonArray("recent_events") { board.timeline.takeLast(10).forEach { add(eventJson(it)) } }
            put("last_comment_id", board.lastCommentId)
        }
    }

    fun claimTask(args: JsonObject?): JsonObject {
        val taskId = args.requireString("task_id")
        val paths = args.stringList("paths")
        if (paths.isEmpty()) throw ToolInputException("paths must list at least one file or folder you will edit")
        val branch = args.requireString("branch")
        val result = client.claimTask(taskId, paths, branch, args.optString("message"))
        return buildJsonObject {
            put("owned", result.owned)
            put("task_id", taskId)
            put("owner", result.task?.owner)
            result.conflict?.let { put("conflict", conflictJson(it)) }
            put(
                "next",
                if (result.owned) "You own '$taskId'. Edit only the claimed paths on branch $branch."
                else "Do NOT edit these files. Use send_message to agree with ${result.conflict?.currentOwner ?: result.task?.owner} who continues.",
            )
        }
    }

    fun sendMessage(args: JsonObject?): JsonObject {
        val posted = client.sendMessage(
            message = args.requireString("message"),
            toAgent = args.optString("to_agent"),
            taskId = args.optString("task_id"),
        )
        return buildJsonObject {
            put("posted", true)
            put("comment_id", posted.commentId)
        }
    }

    /**
     * Blocks until a message, handoff or conflict addressed to this agent (or to everyone) arrives,
     * polling the board, or until the wait limit. Lets an agent sit waiting while its human works.
     */
    fun waitForMessage(args: JsonObject?): JsonObject {
        val since = args?.get("since_id")?.jsonPrimitive?.longOrNull ?: cursorOrNow()
        val messages = pollFor(since, waitMillis(args)) { true }
        return messagesResult(messages, since, waitingFor = null)
    }

    /**
     * Posts [question] to another agent, then blocks until that agent replies (a message from it
     * addressed to this agent or to everyone), or until the wait limit. One call, one answer.
     */
    fun askAgent(args: JsonObject?): JsonObject {
        val toAgent = args.requireString("to_agent")
        if (toAgent == client.agent) throw ToolInputException("to_agent is you; ask another agent")
        val posted = client.sendMessage(
            message = args.requireString("question"),
            toAgent = toAgent,
            taskId = args.optString("task_id"),
        )
        val replies = pollFor(posted.commentId, waitMillis(args)) { it.event.agent == toAgent }
        return buildJsonObject {
            put("asked", true)
            put("question_comment_id", posted.commentId)
            replies.lastOrNull()?.let { cursor = maxOf(cursor ?: 0, it.commentId) }
            if (replies.isEmpty()) {
                put("answered", false)
                put("hint", "No reply from $toAgent yet. Call wait_for_message with since_id=${posted.commentId} to keep waiting; its human may need to tell it to check the board.")
            } else {
                put("answered", true)
                putJsonArray("replies") { replies.forEach { add(eventJson(it)) } }
            }
        }
    }

    private fun waitMillis(args: JsonObject?): Long {
        val requested = args?.get("timeout_seconds")?.jsonPrimitive?.longOrNull
        return if (requested == null) maxWaitMillis else (requested * 1000).coerceIn(0, maxWaitMillis)
    }

    /** Polls until messages for this agent after [since] pass [accept], or [waitMillis] runs out. */
    private fun pollFor(since: Long, waitMillis: Long, accept: (PostedEvent) -> Boolean): List<PostedEvent> {
        val deadline = clock() + waitMillis
        while (true) {
            val found = client.messages(client.agent, since).filter(accept)
            if (found.isNotEmpty() || clock() >= deadline) return found
            sleep(minOf(pollMillis, (deadline - clock()).coerceAtLeast(0)))
        }
    }

    private fun messagesResult(messages: List<PostedEvent>, since: Long, waitingFor: String?): JsonObject {
        val last = messages.lastOrNull()?.commentId ?: since
        cursor = maxOf(cursor ?: 0, last)
        return buildJsonObject {
            putJsonArray("messages") { messages.forEach { add(eventJson(it)) } }
            put("last_comment_id", last)
            put("timed_out", messages.isEmpty())
            if (messages.isEmpty()) put("hint", "Nothing new yet. Call wait_for_message again to keep waiting${waitingFor?.let { " for $it" } ?: ""}.")
        }
    }

    fun getMessages(args: JsonObject?): JsonObject {
        val since = args?.get("since_id")?.jsonPrimitive?.longOrNull ?: 0
        val messages = client.messages(client.agent, since)
        messages.lastOrNull()?.let { cursor = maxOf(cursor ?: 0, it.commentId) }
        return buildJsonObject {
            putJsonArray("messages") { messages.forEach { add(eventJson(it)) } }
            put("last_comment_id", messages.lastOrNull()?.commentId ?: since)
            put("hint", "Pass last_comment_id as since_id next time to get only new messages.")
        }
    }

    fun handoffTask(args: JsonObject?): JsonObject {
        val taskId = args.requireString("task_id")
        val toAgent = args.requireString("to_agent")
        val summary = args.requireString("summary")
        val branch = args.requireString("branch")
        val commit = args.requireString("commit")
        val posted = client.handoffTask(taskId, toAgent, summary, branch, commit)
        val task = client.board().tasks[taskId]
        val accepted = task?.owner == toAgent
        return buildJsonObject {
            put("posted", true)
            put("comment_id", posted.commentId)
            put("accepted", accepted)
            put("owner", task?.owner)
            if (!accepted) put("reason", "Only the current owner of an active task can hand it off.")
        }
    }

    fun releaseTask(args: JsonObject?): JsonObject {
        val taskId = args.requireString("task_id")
        val posted = client.releaseTask(taskId, args.optString("message"))
        val task = client.board().tasks[taskId]
        val released = task != null && task.owner == null
        return buildJsonObject {
            put("posted", true)
            put("comment_id", posted.commentId)
            put("released", released)
            if (!released) put("reason", "Only the current owner of an active task can release it.")
        }
    }

    // --- JSON shapes -------------------------------------------------------

    private fun taskJson(t: TaskState) = buildJsonObject {
        put("task_id", t.taskId)
        put("owner", t.owner)
        put("status", t.status.name.lowercase())
        put("branch", t.branch)
        putJsonArray("paths") { t.paths.forEach { add(JsonPrimitive(it)) } }
        put("last_message", t.lastMessage)
    }

    private fun conflictJson(c: Conflict) = buildJsonObject {
        put("task_id", c.taskId)
        put("claimant", c.claimant)
        put("owner", c.currentOwner)
        putJsonArray("paths") { c.overlappingPaths.forEach { add(JsonPrimitive(it)) } }
        put("reason", c.reason)
        put("comment_id", c.commentId)
    }

    private fun eventJson(p: PostedEvent) = buildJsonObject {
        val e = p.event
        put("comment_id", p.commentId)
        put("at", p.createdAt)
        put("type", e.type)
        put("from", e.agent)
        e.toAgent?.let { put("to", it) }
        e.taskId?.let { put("task_id", it) }
        e.message?.let { put("message", it) }
        e.summary?.let { put("summary", it) }
        e.branch?.let { put("branch", it) }
        e.commit?.let { put("commit", it) }
    }
}

// --- argument helpers ------------------------------------------------------

private fun JsonObject?.optString(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject?.requireString(key: String): String =
    optString(key) ?: throw ToolInputException("Missing required argument '$key'")

/** Accepts a JSON array, or a single string with comma- or newline-separated paths. */
private fun JsonObject?.stringList(key: String): List<String> =
    when (val value: JsonElement? = this?.get(key)) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.split(',', '\n').orEmpty()
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }

/** Used by tests and the server to build argument objects. */
fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
            is Number -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
