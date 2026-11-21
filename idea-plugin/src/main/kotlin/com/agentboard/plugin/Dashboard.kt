package com.agentboard.plugin

import com.agentboard.core.BoardState
import com.agentboard.core.DemoFixtures
import com.agentboard.core.EventType
import com.agentboard.core.PostedEvent
import com.agentboard.core.TaskState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer
import java.util.concurrent.atomic.AtomicReference
import javax.swing.text.DefaultCaret

/**
 * The Agent Board panel: stage picker, conflict banner, agents, tasks and the timeline.
 * Everything shown comes from one [BoardState]; [render] rebuilds the sections from it.
 */
class Dashboard(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JBPanel<Dashboard>(BorderLayout()) {

    private val sources: List<() -> BoardSource> = buildList {
        DemoFixtures.Stage.entries.forEach { stage -> add { FixtureSource(stage) } }
        if (liveAvailable()) add { LiveSource() }
    }
    private val sourceNames = DemoFixtures.Stage.entries.map { FixtureSource(it).label } +
        (if (liveAvailable()) listOf("Live") else emptyList())

    private val stageBox = ComboBox(sourceNames.toTypedArray())
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh"
        putClientProperty("JButton.buttonType", "square")
    }
    private val statusLabel = plainLabel("").apply { foreground = JBColor.GRAY }
    private val content = ScrollablePanel()
    private val scrollPane = JBScrollPane(content).apply { border = JBUI.Borders.empty() }
    private var scrollToTopOnRender = true

    private var currentSource: BoardSource? = null
    @Volatile private var disposed = false
    /** The source with a load in flight, if any. Switching stage starts a new load right away. */
    private val inFlight = AtomicReference<BoardSource?>(null)
    private var lastRendered: BoardState? = null

    /** Polls the live Issue every 5 seconds; fixtures never change, so they are not polled. */
    private val refreshTimer = Timer(REFRESH_MS) { if (currentSource is LiveSource) reload() }
    private val currentAgent: String = System.getenv("AGENT_BOARD_AGENT")?.takeIf { it.isNotBlank() } ?: LiveSource.DEFAULT_AGENT
    private val repo: String = System.getenv("AGENT_BOARD_REPO")?.takeIf { it.isNotBlank() } ?: "agtheproducer/Huddle"

    init {
        val controls = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(stageBox)
            add(refreshButton)
        }
        // Status sits on its own line so the toolbar fits a narrow tool window.
        val toolbar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(statusLabel.apply { border = JBUI.Borders.empty(0, 8, 2, 8) }, BorderLayout.SOUTH)
        }
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        stageBox.addActionListener { selectSource(stageBox.selectedIndex) }
        refreshButton.addActionListener { reload() }
        Disposer.register(parentDisposable) {
            disposed = true
            refreshTimer.stop()
        }
        refreshTimer.start()
        // Startup stage: AGENT_BOARD_STAGE if set (e.g. "conflict"), else Live when the live board is
        // configured, else the first demo stage. A configured laptop should never open on staged data.
        val requested = System.getenv("AGENT_BOARD_STAGE")
            ?.let { wanted -> sourceNames.indexOfFirst { it.equals(wanted, ignoreCase = true) } }
            ?.takeIf { it >= 0 }
        val initial = requested ?: sourceNames.indexOf("Live").takeIf { it >= 0 } ?: 0
        stageBox.selectedIndex = initial  // fires the listener, which loads the board
        if (initial == 0) selectSource(0)
    }

    private fun selectSource(index: Int) {
        currentSource = runCatching { sources[index]() }.getOrElse { e ->
            showError("Could not open board: ${e.message}")
            null
        }
        scrollToTopOnRender = true
        lastRendered = null
        reload()
    }

    /**
     * Loads the current source off the UI thread, then renders on it. GitHub is never called on the UI thread.
     * The same source is not loaded twice at once, so a slow network cannot pile up requests.
     */
    fun reload() {
        val source = currentSource ?: return
        if (inFlight.getAndSet(source) === source) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                runCatching { source.load() }
            } finally {
                inFlight.compareAndSet(source, null)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || source !== currentSource) return@invokeLater
                result.fold(
                    onSuccess = { state ->
                        // Skip the rebuild when nothing changed, so a 5-second poll does not flicker or move the scroll.
                        if (state != lastRendered) render(state)
                        showStatus("Last updated ${CLOCK_FORMAT.format(Instant.now())}")
                    },
                    // Keep the last good board on screen and say what went wrong.
                    onFailure = { showError("Could not load board: ${it.message ?: it.javaClass.simpleName}") },
                )
            }
        }
    }

    private fun showStatus(message: String) {
        statusLabel.text = message
        statusLabel.foreground = JBColor.GRAY
    }

    private fun showError(message: String) {
        statusLabel.text = message
        statusLabel.foreground = JBColor.RED
    }

    /** Rebuilds every section from [state]. Call on the UI thread. */
    fun render(state: BoardState) {
        lastRendered = state
        content.removeAll()

        state.openConflicts.forEach { c ->
            val files = c.overlappingPaths.map(::fileName).ifEmpty { listOfNotNull(c.taskId) }.joinToString()
            content.add(banner("Conflict: ${c.claimant} and ${c.currentOwner} both claimed $files", c.reason))
        }

        // The latest handoff is the moment the demo is about, so keep it above the fold.
        state.timeline.lastOrNull { it.event.type == EventType.HANDOFF }?.let { content.add(handoffCard(it, latest = true)) }

        // Newest events first, so a message arriving during a live demo is visible without scrolling.
        // The full timeline below stays oldest-first.
        val recent = state.timeline.takeLast(RECENT_COUNT).asReversed()
        if (recent.isNotEmpty()) {
            content.add(sectionTitle("Recent"))
            recent.forEach { p -> content.add(timelineRow(p)) }
        }

        content.add(sectionTitle("Agents"))
        if (state.agents.isEmpty()) content.add(muted("No agents yet."))
        state.agents.values.sortedBy { it.agent }.forEach { a ->
            content.add(card(highlight = a.agent == currentAgent) {
                add(bold(a.agent))
                add(text("Task: ${a.activeTasks.joinToString().ifEmpty { "none" }}"))
                add(text("Branch: ${a.branch ?: "–"}"))
                add(muted("Last seen ${formatTime(a.lastSeen)}"))
                val accounts = accountsFor(state, a.agent)
                when {
                    accounts.size == 1 -> add(muted("Posts via ${accounts.single()}"))
                    // The agent field is self-declared, so one agent posting from several accounts is worth flagging.
                    accounts.size > 1 -> add(text("Posts via ${accounts.joinToString()}: more than one account", CONFLICT_BORDER))
                }
            })
        }

        content.add(sectionTitle("Tasks"))
        if (state.tasks.isEmpty()) content.add(muted("No tasks yet."))
        state.tasks.values.sortedBy { it.claimedAtCommentId }.forEach { t -> content.add(taskCard(t)) }

        content.add(sectionTitle("Timeline"))
        if (state.timeline.isEmpty()) content.add(muted("No events yet."))
        state.timeline.forEach { p -> content.add(if (p.event.type == EventType.HANDOFF) handoffCard(p) else timelineRow(p)) }

        content.revalidate()
        content.repaint()
        if (scrollToTopOnRender) {
            scrollToTopOnRender = false
            // After layout, so the new height is known.
            ApplicationManager.getApplication().invokeLater { scrollPane.verticalScrollBar.value = 0 }
        }
    }

    private fun taskCard(t: TaskState) = card(highlight = t.owner == currentAgent) {
        add(bold(t.taskId))
        add(text("Owner: ${t.owner ?: "free"} · ${t.status.name.lowercase()}"))
        if (t.paths.isNotEmpty()) add(text("Files: ${t.paths.joinToString(transform = ::fileName)}"))
        add(text("Branch: ${t.branch ?: "–"}"))
        t.lastMessage?.let { add(muted(it)) }
    }

    private fun timelineRow(p: PostedEvent): JComponent {
        val e = p.event
        val who = if (e.toAgent != null) "${e.agent} → ${e.toAgent}" else e.agent
        val body = e.message ?: e.summary ?: e.taskId ?: ""
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(3, 8)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(muted("${formatTime(p.createdAt)}  $who${via(p)}  [${typeLabel(e.type)}]"))
            if (body.isNotBlank()) add(text(body))
        }.fillWidth()
    }

    private fun handoffCard(p: PostedEvent, latest: Boolean = false): JComponent {
        val e = p.event
        val title = (if (latest) "Latest handoff: " else "Handoff: ") + "${e.agent} → ${e.toAgent ?: "?"}"
        return card(background = HANDOFF_BG) {
            add(text(title).apply { font = JBFont.label().asBold() })
            add(muted("${formatTime(p.createdAt)} · task ${e.taskId ?: "?"}${via(p)}"))
            e.summary?.let { add(text(it)) }
            add(text("Branch: ${e.branch ?: "–"}"))
            e.commit?.let { commit ->
                val url = "https://github.com/$repo/commit/$commit"
                add(HyperlinkLabel("Commit $commit").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    addHyperlinkListener { BrowserUtil.browse(url) }
                })
            }
        }
    }

    // ---- who really posted ----
    // `agent` is whatever the poster typed; `author` is the GitHub account that posted the comment.

    /** " · via login" when the GitHub account is known and differs from the agent name, else "". */
    private fun via(p: PostedEvent): String =
        p.author.takeIf { it.isNotBlank() && it != p.event.agent }?.let { " · via $it" } ?: ""

    /** GitHub accounts that posted as [agent], in first-seen order. Fixtures and memory stores have none. */
    private fun accountsFor(state: BoardState, agent: String): List<String> =
        state.timeline.filter { it.event.agent == agent }
            .map { it.author }
            .filter { it.isNotBlank() && it != agent && it != "local" }
            .distinct()

    // ---- small building blocks ----

    private fun banner(title: String, reason: String) = card(background = CONFLICT_BG, borderColor = CONFLICT_BORDER) {
        add(text(title).apply { font = JBFont.label().asBold() })
        add(text(reason))
    }

    private fun card(
        highlight: Boolean = false,
        background: Color? = null,
        borderColor: Color = if (highlight) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border(),
        build: JBPanel<*>.() -> Unit,
    ): JComponent = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(3, 8),
            JBUI.Borders.compound(JBUI.Borders.customLine(borderColor, if (highlight) 2 else 1), JBUI.Borders.empty(6, 8)),
        )
        if (background != null) {
            isOpaque = true
            this.background = background
        } else {
            isOpaque = false
        }
        alignmentX = Component.LEFT_ALIGNMENT
        build()
    }.fillWidth()

    private fun sectionTitle(title: String) = plainLabel(title).apply {
        font = JBFont.label().asBold().biggerOn(1f)
        border = JBUI.Borders.empty(10, 8, 2, 8)
    }

    private fun bold(s: String) = plainLabel(s).apply { font = JBFont.label().asBold() }

    /** Wrapping, non-editable plain text: board content comes from Issue comments, so never render it as HTML. */
    private fun text(s: String, color: Color? = null) = JBTextArea().apply {
        // Set before the text: otherwise each text area scrolls itself into view and the board opens mid-way down.
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        text = s
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(1, 0)
        font = JBFont.label()
        color?.let { foreground = it }
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun muted(s: String) = text(s, JBColor.GRAY)

    private fun JComponent.fillWidth(): JComponent = apply {
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    /** Content panel that follows the viewport width, so wrapped text fits the tool window. */
    private class ScrollablePanel : JBPanel<ScrollablePanel>(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(8)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) =
            if (o == SwingConstants.VERTICAL) r.height else r.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val CONFLICT_BG = JBColor(Color(0xFFF1E0), Color(0x4A3424))
        val CONFLICT_BORDER = JBColor(Color(0xE8833A), Color(0xD9822B))
        val HANDOFF_BG = JBColor(Color(0xE8F2FF), Color(0x25324A))
        const val REFRESH_MS = 5_000
        const val RECENT_COUNT = 3
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun fileName(path: String) = path.substringAfterLast('/')

        fun formatTime(iso: String): String =
            runCatching { TIME_FORMAT.format(Instant.parse(iso)) }.getOrDefault(iso.ifBlank { "–" })

        fun typeLabel(type: String) = when (type) {
            EventType.TASK_CLAIMED -> "claim"
            EventType.TASK_RELEASED -> "released"
            EventType.TASK_DONE -> "done"
            else -> type
        }

        /** Labels show untrusted text from Issue comments: switch off Swing's HTML rendering. */
        fun plainLabel(s: String) = JBLabel(s).apply {
            putClientProperty("html.disable", true)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }
}
