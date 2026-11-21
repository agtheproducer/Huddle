package com.agentboard.cli

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.BoardStore
import com.agentboard.core.DemoFixtures
import com.agentboard.core.EventCodec
import com.agentboard.core.GitHubIssueStore

private class Usage(message: String) : IllegalArgumentException(message)

fun main(args: Array<String>) {
    try {
        if (args.isEmpty()) throw Usage(help())
        val store = GitHubIssueStore.fromEnvironment()
        val agent = System.getenv("AGENT_BOARD_AGENT")?.takeIf { it.isNotBlank() }
            ?: throw Usage("Set AGENT_BOARD_AGENT to this agent's id (for example, codex).")
        val client = BoardClient(store, agent)
        when (args[0]) {
            "board" -> {
                requireCount(args, 1, "board")
                printBoard(client.board())
            }
            "claim" -> {
                if (args.size < 4) throw Usage("Usage: claim <task_id> <branch> <path>...")
                val result = client.claimTask(args[1], args.drop(3), args[2])
                if (result.owned) println("Claimed '${args[1]}' as $agent on ${args[2]}.")
                else println("Conflict: ${result.conflict?.reason ?: "the task was not assigned to you; run board for details."}")
            }
            "say" -> {
                val toIndex = args.indexOf("--to")
                if (toIndex >= 0 && toIndex != args.size - 2) throw Usage("Usage: say <message> [--to agent]")
                val message = args.slice(1 until (if (toIndex >= 0) toIndex else args.size)).joinToString(" ").trim()
                if (message.isEmpty()) throw Usage("Usage: say <message> [--to agent]")
                val recipient = if (toIndex >= 0) args.last() else null
                val posted = client.sendMessage(message, recipient)
                println("Posted message #${posted.commentId}${recipient?.let { " to $it" } ?: " to everyone"}.")
            }
            "handoff" -> {
                if (args.size < 6) throw Usage("Usage: handoff <task_id> <to_agent> <branch> <commit> <summary>")
                val posted = client.handoffTask(args[1], args[2], args.drop(5).joinToString(" "), args[3], args[4])
                val state = client.board()
                if (state.tasks[args[1]]?.owner == args[2] && state.rejected.none { it.posted.commentId == posted.commentId })
                    println("Handed '${args[1]}' to ${args[2]} at ${args[3]}@${args[4]}.")
                else println("Handoff was rejected: ${state.rejected.find { it.posted.commentId == posted.commentId }?.reason ?: "check board"}.")
            }
            "release" -> {
                requireCount(args, 2, "release <task_id>")
                val posted = client.releaseTask(args[1])
                val state = client.board()
                val reason = state.rejected.find { it.posted.commentId == posted.commentId }?.reason
                if (reason == null) println("Released '${args[1]}'.") else println("Release was rejected: $reason.")
            }
            "replay-demo" -> replayDemo(store, args.drop(1))
            else -> throw Usage(help())
        }
    } catch (error: Exception) {
        val message = error.message.orEmpty()
        val readable = when {
            message.contains("GitHub returned 401") -> "GitHub rejected GITHUB_TOKEN. Set a valid token with Issues access."
            message.contains("GitHub returned 403") -> "GitHub denied Issue access. Check token permissions and repository access."
            message.contains("GitHub returned 404") -> "Issue not found. Check AGENT_BOARD_REPO and AGENT_BOARD_ISSUE."
            message.isNotBlank() -> message.lineSequence().first()
            else -> "Command failed; check GitHub connectivity and configuration."
        }
        System.err.println("Agent Board: $readable")
        kotlin.system.exitProcess(1)
    }
}

private fun requireCount(args: Array<String>, count: Int, usage: String) {
    if (args.size != count) throw Usage("Usage: $usage")
}

private fun printBoard(board: BoardState) {
    println("Agent Board · last comment #${board.lastCommentId}")
    println("Tasks:")
    if (board.tasks.isEmpty()) println("  (none)")
    board.tasks.values.sortedBy { it.taskId }.forEach { task ->
        println("  ${task.taskId}: ${task.owner ?: "unassigned"} · ${task.status.name.lowercase()} · ${task.branch ?: "no branch"}")
        if (task.paths.isNotEmpty()) println("    ${task.paths.joinToString()}")
    }
    println("Open conflicts:")
    if (board.openConflicts.isEmpty()) println("  (none)")
    board.openConflicts.forEach { conflict -> println("  #${conflict.commentId}: ${conflict.reason}") }
    println("Recent events:")
    if (board.timeline.isEmpty()) println("  (none)")
    board.timeline.takeLast(10).forEach { posted ->
        val event = posted.event
        println("  #${posted.commentId} ${event.agent} · ${event.type}${event.taskId?.let { " · $it" } ?: ""}: " +
            (event.message ?: event.summary ?: ""))
    }
}

private fun replayDemo(store: BoardStore, options: List<String>) {
    if (options.isNotEmpty() && (options.size != 2 || options[0] != "--delay"))
        throw Usage("Usage: replay-demo [--delay seconds]")
    val delay = if (options.isEmpty()) 3L else options[1].toLongOrNull()
        ?: throw Usage("--delay must be a whole number of seconds (0–30).")
    if (delay < 0 || delay > 30) throw Usage("--delay must be 0–30 seconds.")

    // A unique namespace lets the story be replayed on a populated Issue without
    // silently colliding with real task claims or an earlier rehearsal.
    val runId = System.currentTimeMillis().toString(36)
    println("SIMULATED demo $runId: fixture messages and commit a91f2c7 are staged, not live agent work.")
    DemoFixtures.events.forEachIndexed { index, original ->
        val event = original.copy(
            taskId = original.taskId?.let { "demo-$runId-$it" },
            paths = original.paths.map { "demo/$runId/$it" },
            message = original.message?.let { "[DEMO] $it" },
            summary = original.summary?.let { "[DEMO] $it" },
        )
        val posted = store.postComment(EventCodec.toCommentBody(event))
        val stage = when (index) { 1 -> "START"; 3 -> "CONFLICT"; 5 -> "HANDOFF"; 7 -> "RESOLVED"; else -> null }
        println("  ${index + 1}/8 #${posted.id} ${event.agent} ${event.type}${stage?.let { " · $it" } ?: ""}")
        if (index < DemoFixtures.events.lastIndex) Thread.sleep(delay * 1_000)
    }
    println("Replay complete. Run 'board' or watch the IDEA tool window refresh.")
}

private fun help() = "Usage: board | claim <task_id> <branch> <path>... | say <message> [--to agent] | " +
    "handoff <task_id> <to_agent> <branch> <commit> <summary> | release <task_id> | replay-demo [--delay seconds]"
