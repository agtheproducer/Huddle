package com.agentboard.core

/**
 * The judges' demo story as fixture events, so the MCP server and the tool window
 * can be built before the GitHub Issue is wired in.
 *
 * Stages: START → CONFLICT → HANDOFF → RESOLVED.
 */
object DemoFixtures {
    const val DASHBOARD = "idea-plugin/src/main/kotlin/com/agentboard/plugin/Dashboard.kt"

    enum class Stage(val eventCount: Int) { START(2), CONFLICT(4), HANDOFF(6), RESOLVED(8) }

    val events: List<BoardEvent> = listOf(
        // START
        BoardEvent(type = EventType.TASK_CLAIMED, agent = "julieta-claude", taskId = "dashboard", branch = "agent/dashboard",
            paths = listOf(DASHBOARD), message = "I'm taking the dashboard, editing Dashboard.kt."),
        BoardEvent(type = EventType.TASK_CLAIMED, agent = "codex", taskId = "core-tests", branch = "agent/core-tests",
            paths = listOf("core/src/test/kotlin/com/agentboard/core/BoardReducerTest.kt"), message = "Writing reducer tests."),
        // CONFLICT
        BoardEvent(type = EventType.TASK_CLAIMED, agent = "amit-claude", taskId = "dashboard-ui", branch = "agent/dashboard-ui",
            paths = listOf(DASHBOARD), message = "Starting the dashboard UI."),
        BoardEvent(type = EventType.MESSAGE, agent = "amit-claude", toAgent = "julieta-claude", taskId = "dashboard",
            message = "I started that file too. What have you built?"),
        // HANDOFF
        BoardEvent(type = EventType.HANDOFF, agent = "julieta-claude", taskId = "dashboard", toAgent = "amit-claude",
            summary = "Cards and state model are committed. Charts need wiring. You can take over.",
            branch = "agent/dashboard", commit = "a91f2c7"),
        BoardEvent(type = EventType.MESSAGE, agent = "amit-claude", toAgent = "julieta-claude", taskId = "dashboard",
            message = "Read a91f2c7. I'll take the dashboard and wire the charts."),
        // RESOLVED
        BoardEvent(type = EventType.TASK_CLAIMED, agent = "julieta-claude", taskId = "plugin-tests", branch = "agent/plugin-tests",
            paths = listOf("idea-plugin/src/test/kotlin/com/agentboard/plugin/DashboardTest.kt"), message = "Moving to plugin tests."),
        BoardEvent(type = EventType.PROGRESS, agent = "amit-claude", taskId = "dashboard", message = "Charts wired, pushing shortly."),
    )

    /** Comments as they would appear on the Issue, ids 1..n, one minute apart. */
    fun comments(stage: Stage = Stage.RESOLVED): List<RawComment> =
        events.take(stage.eventCount).mapIndexed { i, e ->
            RawComment(
                id = (i + 1).toLong(),
                // padStart rather than String.format, which is JVM-only.
                createdAt = "2026-11-22T10:${i.toString().padStart(2, '0')}:00Z",
                author = e.agent,
                body = EventCodec.toCommentBody(e),
            )
        }

    fun state(stage: Stage = Stage.RESOLVED): BoardState =
        BoardReducer.reduce(EventCodec.parseAll(comments(stage)))
}
