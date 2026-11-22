package com.agentboard.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agentboard.core.BoardClient
import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.HttpBoardStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the board comes from: the live HTTP board, or one of the demo stages (same as the plugin's picker). */
enum class Source(val label: String, val stage: DemoFixtures.Stage?) {
    START("Start", DemoFixtures.Stage.START),
    CONFLICT("Conflict", DemoFixtures.Stage.CONFLICT),
    HANDOFF("Handoff", DemoFixtures.Stage.HANDOFF),
    RESOLVED("Resolved", DemoFixtures.Stage.RESOLVED),
    HTTP("HTTP", null),
}

/**
 * The phone's view of the board: the same Kotlin core (HttpBoardStore + BoardClient + BoardReducer)
 * that the IntelliJ plugin and the agents' MCP server use, polled every few seconds.
 * Network calls run on Dispatchers.Default; state is read by Compose on the main thread.
 */
class BoardModel(private val scope: CoroutineScope) {

    /** Who is using the phone: "amit" posts as amit, and amit-claude is "your agent". */
    var name by mutableStateOf(Platform.load(KEY_NAME))
        private set
    var boardUrl by mutableStateOf(Platform.load(KEY_URL) ?: DEFAULT_URL)
        private set

    var source by mutableStateOf(Source.HTTP)
        private set
    var state by mutableStateOf<BoardState?>(null)
        private set
    var live by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    /** Clock time of the last successful read, e.g. "08:01:24". */
    var lastUpdated by mutableStateOf<String?>(null)
        private set

    /** The recipient / open conversation: null is everyone; otherwise one agent or person. */
    var conversation by mutableStateOf<String?>(null)

    val agentId: String get() = name?.let { "$it-claude" }.orEmpty()

    private var store = HttpBoardStore(boardUrl)
    private var poller: Job? = null

    fun join(newName: String) {
        val clean = clean(newName)
        if (clean.isEmpty()) return
        name = clean
        Platform.save(KEY_NAME, clean)
        start()
    }

    fun signOut() {
        poller?.cancel()
        name = null
        conversation = null
        Platform.save(KEY_NAME, null)
    }

    fun changeUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty() || clean == boardUrl) return
        boardUrl = clean
        Platform.save(KEY_URL, clean)
        store = HttpBoardStore(clean)
        if (source == Source.HTTP) {
            state = null
            refresh()
        }
    }

    /** Switches between the live board and a demo stage. */
    fun pick(s: Source) {
        source = s
        conversation = null
        error = null
        val stage = s.stage
        if (stage != null) {
            state = DemoFixtures.state(stage)
            live = false
            lastUpdated = Platform.nowClock()
        } else {
            state = null
            refresh()
        }
    }

    /** Starts polling (idempotent). Demo stages are static, so polling only reads in HTTP mode. */
    fun start() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                load()
                delay(POLL_MS)
            }
        }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun send(text: String): Boolean {
        val me = name ?: return false
        val body = text.trim()
        if (body.isEmpty() || sending) return false
        if (source != Source.HTTP) {
            error = "Switch to HTTP to message the agents."
            return false
        }
        sending = true
        val to = conversation
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { BoardClient(store, me).sendMessage(body, toAgent = to) }
            }
            sending = false
            result.onFailure { error = "Couldn't send: ${it.message ?: it::class.simpleName}" }
            load()
        }
        return true
    }

    private suspend fun load() {
        if (source != Source.HTTP) return
        val reader = name ?: "phone"
        val result = withContext(Dispatchers.Default) { runCatching { BoardClient(store, reader).board() } }
        if (source != Source.HTTP) return
        result.fold(
            onSuccess = {
                state = it
                live = true
                error = null
                lastUpdated = Platform.nowClock()
            },
            onFailure = {
                live = false
                error = it.message ?: it::class.simpleName
            },
        )
    }

    fun isMine(agent: String) = name != null && (agent == name || agent == agentId)

    companion object {
        const val POLL_MS = 3_000L
        /** The team board, as published in the repo's .bob/board.json. Changeable in Settings. */
        const val DEFAULT_URL = "https://crawling-reflector-nail.ngrok-free.dev"
        /** Where handoff commits open. */
        const val REPO = "agtheproducer/bob"
        private const val KEY_NAME = "bob.name"
        private const val KEY_URL = "bob.url"

        /** "Amit G." -> "amit": lowercase letters and digits, first word only. */
        fun clean(raw: String): String =
            raw.trim().lowercase().substringBefore(' ').filter { it.isLetterOrDigit() || it == '-' }.substringBefore('-')
    }
}
