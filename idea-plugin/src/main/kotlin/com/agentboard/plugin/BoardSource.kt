package com.agentboard.plugin

import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.GitHubIssueStore
import com.agentboard.core.HttpBoardStore

/** Where the tool window gets its board from: a demo fixture stage or the live GitHub Issue. */
interface BoardSource {
    val label: String
    fun load(): BoardState

    /**
     * A client that posts to this board as [sender], or null for read-only demo stages.
     * Lets the person at the IDE talk to the agents. Post off the UI thread.
     */
    fun clientAs(sender: String): BoardClient? = null
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

    override fun clientAs(sender: String): BoardClient =
        BoardClient(GitHubIssueStore.fromEnvironment(config.asEnvironment()), sender)

    companion object {
        const val LABEL = "Live"
    }
}

/**
 * The board from an HTTP server. Real-time, no GitHub token needed.
 * Calls the server on every [load]: call it from a background thread, never the UI thread.
 */
class HttpSource(private val config: BoardConfig) : BoardSource {
    override val label: String = LABEL

    private val client: BoardClient by lazy {
        BoardClient(HttpBoardStore(config.httpUrl), config.agent)
    }

    override fun load(): BoardState = client.board()

    override fun clientAs(sender: String): BoardClient = BoardClient(HttpBoardStore(config.httpUrl), sender)

    companion object {
        const val LABEL = "HTTP"
    }
}
