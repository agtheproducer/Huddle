package com.agentboard.plugin

import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The board for one project: which source is shown, polling, the latest state, sending, and auto-reply.
 *
 * The tool window only displays a [Snapshot] and forwards clicks here, so there is one poller and at most
 * one auto-reply per project however many times the tool window is opened, and polling keeps running when
 * it is closed. Network work runs on [Dispatchers.IO] in the service's own coroutine scope (cancelled with
 * the project); everything that touches state or listeners runs on [Dispatchers.EDT].
 */
@Service(Service.Level.PROJECT)
class BoardService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    /** Everything the tool window needs to draw itself. */
    data class Snapshot(
        val sourceNames: List<String>,
        val selectedIndex: Int,
        val live: Boolean,
        val config: BoardConfig,
        val state: BoardState?,
        val error: String?,
        val loadedAt: Instant?,
        val autoReplyOn: Boolean,
        val autoStatus: String?,
        /** A first name to offer as the agent id while none is set ("amit" from git's user.name). */
        val suggestedName: String,
    )

    /** Told on the EDT whenever the snapshot changes. */
    fun interface Listener {
        fun changed(snapshot: Snapshot)
    }

    // All of these are read and written on the EDT only; @Volatile for the background load's reads.
    @Volatile var config: BoardConfig = BoardConfig.current()
        private set
    @Volatile private var sourceNames: List<String> = namesFor(config)
    @Volatile private var selectedIndex = 0
    @Volatile private var source: BoardSource? = null
    /** Bumped on every source switch, so a slow load of the previous source is thrown away. */
    @Volatile private var generation = 0
    private var state: BoardState? = null
    private var error: String? = null
    private var loadedAt: Instant? = null
    private var autoStatus: String? = null
    /** The board link the repo publishes (RepoBoardLink), found once in the background. */
    @Volatile private var repoLink: RepoBoardLink.Link? = null
    @Volatile private var suggestedName: String = ""

    private val listeners = CopyOnWriteArrayList<Listener>()
    /** One load at a time: a slow network cannot pile up requests. */
    private val loading = Mutex()

    private val autoReply = AutoReply(
        project,
        onStatus = { autoStatus = it; publish() },
        onRunFinished = { refresh() },
    )

    val isLive: Boolean get() = source is HttpSource || source is LiveSource

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BoardSettingsListener.TOPIC, BoardSettingsListener {
                cs.launch(Dispatchers.EDT) { reconfigure() }
            })
        // Zero-setup: find the board the repo links to and a name to suggest, then reconnect if that changed anything.
        cs.launch(Dispatchers.IO) {
            val link = runCatching { RepoBoardLink.discover(project) }.getOrNull()
            val name = runCatching { RepoBoardLink.suggestedName(project.basePath?.let { java.io.File(it) }) }.getOrDefault("")
            withContext(Dispatchers.EDT) {
                suggestedName = name
                repoLink = link
                if (link != null && config.httpUrl.isBlank()) reconfigure() else publish()
            }
        }
        cs.launch(Dispatchers.EDT) {
            selectInitial()
            // Live boards change; demo stages do not, so they are loaded once and never polled.
            while (isActive) {
                delay(REFRESH_MS)
                if (isLive) load()
            }
        }
    }

    fun snapshot() = Snapshot(
        sourceNames, selectedIndex, isLive, config, state, error, loadedAt, autoReply.enabled, autoStatus, suggestedName,
    )

    /**
     * First run: saves [name] as this person's agent id ("amit" → "amit-claude") and reconnects.
     * Call on the EDT. Returns why the name was refused, or null.
     */
    fun join(name: String): String? {
        val clean = name.trim().lowercase().replace(Regex("[^a-z0-9-]"), "")
        if (clean.isEmpty()) return "Type a name, e.g. ${suggestedName.ifBlank { "amit" }}"
        val agent = if ('-' in clean) clean else "$clean-claude"
        BoardSettings.get().state.agent = agent
        ApplicationManager.getApplication().messageBus.syncPublisher(BoardSettingsListener.TOPIC).settingsChanged()
        return null
    }

    /** Adds [listener] until [parent] is disposed, and tells it the current snapshot straight away. */
    fun addListener(parent: Disposable, listener: Listener) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        listener.changed(snapshot())
    }

    /** Shows the source at [index] of the stage list. Call on the EDT. */
    fun select(index: Int) {
        if (index !in sourceNames.indices) return
        selectedIndex = index
        generation++
        state = null
        error = null
        loadedAt = null
        source = runCatching { sourceAt(index) }.getOrElse { e -> error = "Could not open board: ${e.message}"; null }
        if (!isLive && autoReply.enabled) setAutoReply(false)
        publish()
        refresh()
    }

    fun refresh() {
        cs.launch(Dispatchers.EDT) { load() }
    }

    /** Posts [text] as the person at this IDE ([sender]) and refreshes. [done] runs on the EDT. */
    fun send(text: String, to: String?, sender: String, done: (Result<Unit>) -> Unit) {
        val target = source
        cs.launch(Dispatchers.EDT) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = target?.clientAs(sender) ?: error("This board is read-only")
                    client.sendMessage(text, toAgent = to)
                    Unit
                }
            }
            done(result)
            if (result.isSuccess) load()
        }
    }

    /**
     * Turns auto-reply on or off. Returns why it could not be switched on, or null.
     * App-wide guard: one agent id has auto-reply in at most one open project, so two windows
     * never both start Claude for the same message.
     */
    fun setAutoReply(on: Boolean): String? {
        val agent = config.agent
        if (on) {
            if (agent.isBlank()) return "Set your agent id in ⚙ to use auto-reply."
            if (!isLive) return "Auto-reply needs the live board."
            val holder = AUTO_REPLY_OWNERS.putIfAbsent(AutoReply.humanOf(agent), project)
            if (holder != null && holder != project) return "Auto-reply for $agent is already on in project ${holder.name}."
        } else {
            AUTO_REPLY_OWNERS.remove(AutoReply.humanOf(agent), project)
        }
        autoReply.setEnabled(on, state)
        publish()
        return null
    }

    // ---- internals ----

    private suspend fun load() {
        if (!loading.tryLock()) return
        try {
            val target = source ?: return
            val gen = generation
            val result = withContext(Dispatchers.IO) { runCatching { target.load() } }
            if (gen != generation) return  // the user switched source meanwhile
            result.fold(
                onSuccess = {
                    state = it
                    error = null
                    loadedAt = Instant.now()
                    autoReply.onBoard(it, config, target)
                },
                // Keep the last good board on screen and say what went wrong.
                onFailure = { error = "Could not load board: ${it.message ?: it.javaClass.simpleName}" },
            )
            publish()
        } finally {
            loading.unlock()
        }
    }

    private fun publish() {
        val s = snapshot()
        listeners.forEach { it.changed(s) }
    }

    /**
     * Startup stage: AGENT_BOARD_STAGE if set (e.g. "conflict"), else the HTTP board, else the GitHub
     * board, else the first demo stage. A configured laptop should never open on staged data.
     */
    private fun selectInitial() {
        val requested = System.getenv("AGENT_BOARD_STAGE")
            ?.let { wanted -> sourceNames.indexOfFirst { it.equals(wanted, ignoreCase = true) } }
            ?.takeIf { it >= 0 }
        select(requested ?: preferredLiveIndex() ?: 0)
    }

    /** New settings: rebuild the stage list and reconnect to the best live source. */
    private fun reconfigure() {
        val oldAgent = config.agent
        config = BoardConfig.current().withRepoLink(repoLink)
        if (oldAgent != config.agent && autoReply.enabled) {
            AUTO_REPLY_OWNERS.remove(AutoReply.humanOf(oldAgent), project)
            autoReply.setEnabled(false, null)
        }
        sourceNames = namesFor(config)
        select(preferredLiveIndex() ?: 0)  // always reconnect: the URL, repo, Issue or token may have changed
    }

    private fun preferredLiveIndex(): Int? =
        sourceNames.indexOf(HttpSource.LABEL).takeIf { it >= 0 } ?: sourceNames.indexOf(LiveSource.LABEL).takeIf { it >= 0 }

    private fun sourceAt(index: Int): BoardSource = when (sourceNames[index]) {
        HttpSource.LABEL -> HttpSource(config)
        LiveSource.LABEL -> LiveSource(config)
        else -> FixtureSource(DemoFixtures.Stage.entries[index])
    }

    override fun dispose() {
        AUTO_REPLY_OWNERS.remove(AutoReply.humanOf(config.agent), project)
        autoReply.dispose()
    }

    companion object {
        const val REFRESH_MS = 5_000L

        /** Which project has auto-reply on for each agent id, across all open projects. */
        private val AUTO_REPLY_OWNERS = ConcurrentHashMap<String, Project>()

        private val FIXTURE_NAMES = DemoFixtures.Stage.entries.map { FixtureSource(it).label }

        fun namesFor(c: BoardConfig): List<String> = buildList {
            addAll(FIXTURE_NAMES)
            if (c.httpAvailable) add(HttpSource.LABEL)
            if (c.liveAvailable) add(LiveSource.LABEL)
        }
    }
}
