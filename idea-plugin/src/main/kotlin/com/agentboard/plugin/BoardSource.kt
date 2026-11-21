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
 * The live board from the coordination Issue in [config]. Calls GitHub on every [load], and reads
 * the token from the password safe on the first one: call it from a background thread, never the UI thread.
 */
class LiveSource(private val config: BoardConfig) : BoardSource {
    override val label: String = LABEL

    private val client: BoardClient by lazy {
        BoardClient(GitHubIssueStore.fromEnvironment(config.asEnvironment()), config.agent)
    }

    override fun load(): BoardState = client.board()

    companion object {
        const val LABEL = "Live"
    }
}
