package com.agentboard.plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/**
 * Board settings saved by the IDE (Settings → Tools → Agent Board), so an installed plugin works
 * when IntelliJ is started from the Dock. The GitHub token is never stored here: it lives in the
 * IDE's password safe (the macOS Keychain on a Mac).
 */
@Service(Service.Level.APP)
@State(name = "AgentBoardSettings", storages = [Storage("agentBoard.xml")])
class BoardSettings : PersistentStateComponent<BoardSettings.Values> {

    class Values {
        var repo: String = ""
        var issue: String = ""
        var agent: String = ""
        /** HTTP server URL for real-time mode, e.g. `http://localhost:3000`. */
        var httpUrl: String = ""
        /** Whether a token was saved in the password safe, so the UI thread never has to read it. */
        var tokenSaved: Boolean = false
        /**
         * Whether an unattended auto-reply run may change code (edit files, Gradle, git). Off by default:
         * board messages come from anyone who can reach the board, so unattended runs only answer.
         */
        var autoReplyMayEdit: Boolean = false
    }

    private var values = Values()

    override fun getState(): Values = values
    override fun loadState(state: Values) {
        values = state
    }

    companion object {
        fun get(): BoardSettings = ApplicationManager.getApplication().getService(BoardSettings::class.java)

        private val TOKEN_KEY = CredentialAttributes(generateServiceName("Agent Board", "GitHub token"))

        /** Reads the token from the password safe. Can block: call it off the UI thread. */
        fun readToken(): String? = PasswordSafe.instance.getPassword(TOKEN_KEY)?.takeIf { it.isNotBlank() }

        /** Saves the token, or removes it when [token] is null. Can block: call it off the UI thread. */
        fun writeToken(token: String?) = PasswordSafe.instance.setPassword(TOKEN_KEY, token)
    }
}

/** Told when the settings change, so open tool windows switch to the new board. */
fun interface BoardSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<BoardSettingsListener> = Topic.create("Agent Board settings", BoardSettingsListener::class.java)
    }
}

/**
 * The board this IDE shows, resolved from the saved settings, falling back to the AGENT_BOARD_*
 * environment variables (and GITHUB_TOKEN) for any field left empty.
 */
data class BoardConfig(
    val repo: String,
    val issue: String,
    val agent: String,
    val httpUrl: String,
    private val tokenInSettings: Boolean,
    private val envToken: String?,
    /** Where [httpUrl] came from when nobody typed one: the board link published in the repo, else null. */
    val httpFromRepo: String? = null,
) {
    /** Enough to read the live board: a repo, a numeric Issue and a token from either place. */
    val liveAvailable: Boolean
        get() = repo.isNotBlank() && issue.toIntOrNull() != null && (tokenInSettings || !envToken.isNullOrBlank())

    /** Whether an HTTP server URL is configured. */
    val httpAvailable: Boolean
        get() = httpUrl.isNotBlank()

    /** Uses the board link published in the repo when no URL was typed in Settings or the environment. */
    fun withRepoLink(link: RepoBoardLink.Link?): BoardConfig =
        if (httpUrl.isBlank() && link != null) copy(httpUrl = link.url, httpFromRepo = link.from) else this

    /** The token: the saved one first, else GITHUB_TOKEN. Can block: call it off the UI thread. */
    fun token(): String? = (if (tokenInSettings) BoardSettings.readToken() else null) ?: envToken?.takeIf { it.isNotBlank() }

    /** Same shape as the environment, for `GitHubIssueStore.fromEnvironment`. Can block. */
    fun asEnvironment(): Map<String, String> = buildMap {
        put("AGENT_BOARD_REPO", repo)
        put("AGENT_BOARD_ISSUE", issue)
        put("AGENT_BOARD_AGENT", agent)
        token()?.let { put("GITHUB_TOKEN", it) }
    }

    companion object {
        fun current(env: Map<String, String> = System.getenv()): BoardConfig {
            val saved = BoardSettings.get().state
            fun pick(value: String, key: String) = value.trim().ifBlank { env[key]?.trim().orEmpty() }
            return BoardConfig(
                repo = pick(saved.repo, "AGENT_BOARD_REPO"),
                issue = pick(saved.issue, "AGENT_BOARD_ISSUE"),
                agent = pick(saved.agent, "AGENT_BOARD_AGENT"),
                httpUrl = pick(saved.httpUrl, "AGENT_BOARD_HTTP_URL"),
                tokenInSettings = saved.tokenSaved,
                envToken = env["GITHUB_TOKEN"],
            )
        }
    }
}
