package com.agentboard.plugin

import com.agentboard.core.BoardState
import com.agentboard.core.EventType
import com.agentboard.core.PostedEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Auto-reply: when a message addressed to this laptop's agent lands on the board, start Claude Code
 * headless in the project folder with that message, so the agent answers (and works) unattended.
 *
 * Off by default: every wake-up is a Claude run and spends credits. One run at a time; messages that
 * arrive during a run are picked up by the next check. A rate limit and the prompt's "don't answer
 * pure acknowledgements" rule stop two auto-replying agents from thanking each other forever.
 */
class AutoReply(
    private val project: Project,
    /** Told about progress on the UI thread: a short line, or null when idle. */
    private val onStatus: (String?) -> Unit,
    /** Called on the UI thread when a run ends, so the board can refresh. */
    private val onRunFinished: () -> Unit,
) {
    @Volatile var enabled: Boolean = false
        private set

    /** Highest comment id already handed to a run (or seen when auto-reply was switched on). */
    @Volatile private var lastHandled = 0L
    @Volatile private var running: Process? = null
    @Volatile private var baselinePending = false

    private fun baseline(state: BoardState): Long {
        val cutoff = Instant.now().minusSeconds(CATCH_UP_SECONDS)
        return state.timeline
            .filter { p -> runCatching { Instant.parse(p.createdAt) }.getOrNull()?.isBefore(cutoff) ?: true }
            .maxOfOrNull { it.commentId } ?: 0L
    }
    private val recentRuns = ArrayDeque<Long>()

    /**
     * Switch on or off. Switching on ignores older history but still answers the last [CATCH_UP_SECONDS],
     * so a message sent just before ticking the box (or before an IDE restart) is not lost.
     */
    fun setEnabled(on: Boolean, current: BoardState?) {
        enabled = on
        if (on) {
            // No board loaded yet: set the baseline on the first load instead, never answer all history.
            baselinePending = current == null
            if (current != null) lastHandled = baseline(current)
            onStatus("Auto-reply on: waiting for messages")
        } else {
            running?.destroy()
            onStatus(null)
        }
    }

    fun dispose() {
        enabled = false
        running?.destroy()
    }

    /** Called on the UI thread after each board load. Starts a run when new messages are addressed to [config].agent. */
    fun onBoard(state: BoardState, config: BoardConfig, source: BoardSource) {
        val agent = config.agent
        if (!enabled || agent.isBlank() || running != null) return
        if (baselinePending) {
            baselinePending = false
            lastHandled = baseline(state)
        }
        // Wake for the agent itself, and for its human ("amit-claude" → "amit"): teammates' agents often
        // address the person ("Amit, please claim calc-frontend"), and that work is the agent's to pick up.
        val human = humanOf(agent)
        val addressees = setOf(agent, human)
        val fresh = state.timeline.filter { p ->
            p.commentId > lastHandled &&
                p.event.toAgent in addressees &&
                // Never wake for our own posts; the human writing to the agent is exactly what should wake it.
                p.event.agent != agent &&
                !(p.event.agent == human && p.event.toAgent == human) &&
                p.event.type in WAKE_TYPES &&
                !(p.event.message ?: "").startsWith(ACK_PREFIX)
        }
        if (fresh.isEmpty()) return

        val now = System.currentTimeMillis()
        while (recentRuns.isNotEmpty() && now - recentRuns.first() > RATE_WINDOW_MS) recentRuns.removeFirst()
        if (recentRuns.size >= MAX_RUNS_PER_WINDOW) {
            onStatus("Auto-reply paused: $MAX_RUNS_PER_WINDOW runs in 10 min. It resumes by itself.")
            return
        }
        recentRuns.addLast(now)
        lastHandled = fresh.maxOf { it.commentId }
        start(fresh, config, source)
    }

    private fun start(messages: List<PostedEvent>, config: BoardConfig, source: BoardSource) {
        val agent = config.agent
        val workDir = project.basePath?.let(::File)
        if (workDir == null) {
            onStatus("Auto-reply: no project folder")
            return
        }
        val launcher = File(workDir, "mcp/build/install/mcp/bin/mcp")
        if (!launcher.canExecute()) {
            onStatus("Auto-reply needs the board tools: run Gradle mcp:installDist first")
            return
        }
        val senders = messages.map { it.event.agent }.distinct()
        onStatus("$agent is answering ${senders.joinToString()}…")

        ApplicationManager.getApplication().executeOnPooledThread {
            // A quick visible ack, so the other side knows a reply is coming (real replies take ~10–30 s).
            runCatching {
                val client = source.clientAs(agent)
                senders.forEach { client?.sendMessage("$ACK_PREFIX $agent got your message and is on it.", toAgent = it) }
            }

            val result = runCatching { runClaude(messages, config, workDir, launcher) }
            running = null
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { (code, tail) ->
                        if (code == 0) onStatus(if (enabled) "Auto-reply on: $agent answered. Waiting for messages" else null)
                        else {
                            LOG.warn("Auto-reply run exited with $code:\n$tail")
                            onStatus("Auto-reply run failed (exit $code): ${tail.lines().lastOrNull { it.isNotBlank() }?.take(120) ?: ""}")
                        }
                    },
                    onFailure = {
                        LOG.warn("Auto-reply could not start Claude Code", it)
                        onStatus("Auto-reply could not start Claude Code: ${it.message}")
                    },
                )
                onRunFinished()
            }
        }
    }

    /** Runs `claude -p` through the user's login shell, so PATH (Homebrew, npm, nvm) is the same as in Terminal. */
    private fun runClaude(messages: List<PostedEvent>, config: BoardConfig, workDir: File, launcher: File): Pair<Int, String> {
        val mcpConfig = File.createTempFile("agent-board-mcp", ".json").apply { deleteOnExit() }
        mcpConfig.writeText(mcpConfigJson(config, launcher))

        val pb = ProcessBuilder(
            "/bin/zsh", "-ilc",
            "claude -p \"\$AB_PROMPT\" --mcp-config \"\$AB_MCP\" --strict-mcp-config " +
                "--allowedTools \"\$AB_TOOLS\" --max-turns $MAX_TURNS --output-format text",
        ).directory(workDir).redirectErrorStream(true)
        pb.environment().apply {
            put("AB_PROMPT", prompt(messages, config))
            put("AB_MCP", mcpConfig.absolutePath)
            put("AB_TOOLS", ALLOWED_TOOLS)
        }
        val process = pb.start()
        running = process
        process.outputStream.close()
        val output = StringBuilder()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach {
                output.appendLine(it)
                if (output.length > 8_000) output.delete(0, output.length - 4_000)
            }
        }
        if (!process.waitFor(RUN_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return -1 to "timed out after $RUN_TIMEOUT_MIN min"
        }
        mcpConfig.delete()
        return process.exitValue() to output.toString().takeLast(2_000)
    }

    private fun prompt(messages: List<PostedEvent>, config: BoardConfig): String {
        val human = humanOf(config.agent)
        val inbox = messages.joinToString("\n") { p ->
            val e = p.event
            val body = e.message ?: e.summary ?: ""
            val extra = listOfNotNull(e.taskId?.let { "task $it" }, e.branch?.let { "branch $it" }, e.commit?.let { "commit $it" })
            "- #${p.commentId} [${e.type}] from ${e.agent} to ${e.toAgent}${if (extra.isEmpty()) "" else " (${extra.joinToString()})"}: $body"
        }
        return """
            You are ${config.agent}, $human's coding agent on the Agent Board for this repository.
            You were woken up automatically because these board events are addressed to you:
            $inbox

            Do this, then stop:
            1. Call get_board to see who owns which task and files.
            2. Answer each event with send_message to its sender. Be brief and concrete.
            3. If someone asks you to do work: claim_task the files before editing. If owned=false, do not edit; agree with the owner via send_message.
               Commit and push only if asked. Hand work over only with handoff_task and a pushed branch and commit.
            4. Events addressed to "$human" are for your human. Pick up the work in them (claim tasks, build, answer
               questions about the project) and reply as ${config.agent}. Do not answer personal things for $human
               (meetings, coffee, opinions, promises about his time): reply once that $human will answer himself.
            5. Never answer pure acknowledgements, thanks or messages starting with "$ACK_PREFIX". No small talk. If nothing needs an answer, stop without posting.
            The project rules are in AGENTS.md.
        """.trimIndent()
    }

    private fun mcpConfigJson(config: BoardConfig, launcher: File): String {
        val env = buildMap {
            put("AGENT_BOARD_AGENT", config.agent)
            if (config.httpAvailable) put("AGENT_BOARD_HTTP_URL", config.httpUrl)
            else putAll(config.asEnvironment())
            (System.getenv("JAVA_HOME") ?: System.getProperty("java.home"))?.let { put("JAVA_HOME", it) }
        }
        val envJson = env.entries.joinToString(",") { (k, v) -> "${json(k)}:${json(v)}" }
        return """{"mcpServers":{"agent-board":{"command":${json(launcher.absolutePath)},"env":{$envJson}}}}"""
    }

    private fun json(s: String) = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private companion object {
        /** The person behind an agent id: "amit-claude" → "amit", the name the message bar posts as. */
        fun humanOf(agent: String) = agent.substringBefore('-').ifBlank { "human" }

        val LOG = Logger.getInstance(AutoReply::class.java)
        val WAKE_TYPES = setOf(EventType.MESSAGE, EventType.HANDOFF, EventType.CONFLICT)
        const val ACK_PREFIX = "[auto]"
        const val MAX_TURNS = 40
        const val CATCH_UP_SECONDS = 120L
        const val RUN_TIMEOUT_MIN = 5L
        const val MAX_RUNS_PER_WINDOW = 8
        const val RATE_WINDOW_MS = 10 * 60 * 1000L
        /** Board tools, reading the repo, editing files and git: enough to answer and to work, nothing else. */
        const val ALLOWED_TOOLS = "mcp__agent-board,Read,Glob,Grep,Edit,Write,Bash(git:*),Bash(./gradlew:*)"
    }
}
