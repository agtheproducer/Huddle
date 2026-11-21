package com.agentboard.plugin

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.GitHubIssueStore

/** Where the tool window gets its board from: a demo fixture stage or the live GitHub Issue. */
interface BoardSource {
    val label: String
    fun load(): BoardState
}

/** One of the four demo stages, built from `DemoFixtures`. No network. */
class FixtureSource(private val stage: DemoFixtures.Stage) : BoardSource {
    override val label: String = stage.name.lowercase().replaceFirstChar { it.uppercase() }
    override fun load(): BoardState = DemoFixtures.state(stage)
}

/**
 * The live board from the coordination Issue. Calls GitHub on every [load]:
 * call it from a background thread, never the UI thread.
 */
class LiveSource(env: Map<String, String> = System.getenv()) : BoardSource {
    val agent: String = env["AGENT_BOARD_AGENT"]?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT
    private val client = BoardClient(GitHubIssueStore.fromEnvironment(env), agent)

    override val label: String = "Live"
    override fun load(): BoardState = client.board()

    companion object {
        const val DEFAULT_AGENT = "julieta-claude"
    }
}

private val LIVE_ENV = listOf("AGENT_BOARD_REPO", "AGENT_BOARD_ISSUE", "GITHUB_TOKEN")

/** True when the env vars needed for [LiveSource] are all set. */
fun liveAvailable(env: Map<String, String> = System.getenv()): Boolean =
    LIVE_ENV.all { !env[it].isNullOrBlank() }
