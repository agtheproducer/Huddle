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
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.text.DefaultCaret

/**
 * The Agent Board panel: stage picker, conflict banner, agents, tasks and the timeline.
 * Everything shown comes from one [BoardState]; [render] rebuilds the sections from it.
 */
class Dashboard(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JBPanel<Dashboard>(BorderLayout()) {

    /**
     * The board itself lives in a project service: this panel only draws its snapshots and forwards clicks.
     * So one project has one poller and one auto-reply, however many times the tool window is opened.
     */
    private val service = project.service<BoardService>()
    private var snapshot: BoardService.Snapshot = service.snapshot()
    private val config: BoardConfig get() = snapshot.config

    private val stageBox = ComboBox(DefaultComboBoxModel(snapshot.sourceNames.toTypedArray()))
    /** True while the panel itself updates the combo, so its selection events are not sent back to the service. */
    private var updatingModel = false
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh"
        putClientProperty("JButton.buttonType", "square")
    }
    private val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Agent Board settings"
        putClientProperty("JButton.buttonType", "square")
    }
    private val statusLabel = plainLabel("").apply { foreground = JBColor.GRAY }

    /** Off by default: when on, a message for this laptop's agent starts Claude Code to answer it. */
    private val autoReplyBox = JBCheckBox("Auto-reply")
    private val autoLabel = plainLabel("").apply {
        foreground = JBUI.CurrentTheme.Focus.focusColor()
        isVisible = false
    }
    private val content = ScrollablePanel()
    private val scrollPane = JBScrollPane(content).apply { border = JBUI.Borders.empty() }
    private var scrollToTopOnRender = true
    private var lastRendered: BoardState? = null
    private var shownSource = -1

    /** This laptop's agent, whose cards are outlined. Empty means none. */
    private val currentAgent: String get() = config.agent
    /**
     * Who the person at this IDE posts as: the agent id's first part ("amit-claude" → "amit"),
     * so their messages are told apart from their own agent's.
     */
    private val humanSender: String get() = currentAgent.substringBefore('-').ifBlank { "human" }

    // ---- composer: the person at the IDE talks to the agents ----
    private val toBox = ComboBox(DefaultComboBoxModel(arrayOf(EVERYONE))).apply {
        toolTipText = "Who the message is for"
    }
    private val messageField = JBTextField().apply {
        emptyText.text = "Message the agents… (Enter to send)"
    }
    private val sendButton = JButton("Send")
    private val senderLabel = plainLabel("").apply { foreground = JBColor.GRAY }
    private var sending = false

    /** Repo for commit links; the team repo when none is configured (fixtures). */
    private val repo: String get() = config.repo.ifBlank { "agtheproducer/Huddle" }

    init {
        val controls = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(stageBox)
            add(refreshButton)
            add(settingsButton)
            add(autoReplyBox)
        }
        // Status sits on its own line so the toolbar fits a narrow tool window.
        val toolbar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(autoLabel.apply { border = JBUI.Borders.empty(0, 8, 2, 8) }, BorderLayout.CENTER)
            add(statusLabel.apply { border = JBUI.Borders.empty(0, 8, 2, 8) }, BorderLayout.SOUTH)
        }
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(composer(), BorderLayout.SOUTH)

        autoReplyBox.addActionListener {
            service.setAutoReply(autoReplyBox.isSelected)?.let { why ->
                autoReplyBox.isSelected = false
                showError(why)
            }
        }
        sendButton.addActionListener { send() }
        messageField.addActionListener { send() }
        stageBox.addActionListener { if (!updatingModel) service.select(stageBox.selectedIndex) }
        refreshButton.addActionListener { service.refresh() }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, BoardConfigurable::class.java)
        }
        // Called on the EDT with every change, and once straight away.
        service.addListener(parentDisposable) { show(it) }
    }

    /** Draws one snapshot from the service. */
    private fun show(s: BoardService.Snapshot) {
        snapshot = s
        syncStages(s)
        if (s.selectedIndex != shownSource) {
            shownSource = s.selectedIndex
            scrollToTopOnRender = true
            lastRendered = null
        }
        updateComposer()
        autoReplyBox.isSelected = s.autoReplyOn
        autoLabel.text = s.autoStatus ?: ""
        autoLabel.isVisible = s.autoStatus != null
        // Skip the rebuild when nothing changed, so a 5-second poll does not flicker or move the scroll.
        if (s.state != null && s.state != lastRendered) render(s.state)
        when {
            s.error != null -> showError(s.error)
            s.live && s.loadedAt != null -> showStatus("Last updated ${CLOCK_FORMAT.format(s.loadedAt)}")
            s.live -> showStatus("Loading…")
            !config.liveAvailable && !config.httpAvailable -> showStatus("Demo stages only. Set up the live board with ⚙.")
            else -> showStatus("Demo stage")
        }
    }

    /** Keeps the stage combo in step with the service without echoing the change back. */
    private fun syncStages(s: BoardService.Snapshot) {
        val current = (0 until stageBox.itemCount).map { stageBox.getItemAt(it) }
        updatingModel = true
        try {
            if (current != s.sourceNames) stageBox.model = DefaultComboBoxModel(s.sourceNames.toTypedArray())
            if (stageBox.selectedIndex != s.selectedIndex) stageBox.selectedIndex = s.selectedIndex
        } finally {
            updatingModel = false
        }
    }

    private fun composer(): JComponent {
        val row = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
            add(toBox, BorderLayout.WEST)
            add(messageField, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 8))
            add(row, BorderLayout.CENTER)
            add(senderLabel.apply { border = JBUI.Borders.emptyTop(3) }, BorderLayout.SOUTH)
        }
    }

    /** The composer only works on a board that can be posted to: HTTP or Live, never the demo stages. */
    private fun updateComposer() {
        val live = snapshot.live
        listOf(toBox, messageField, sendButton).forEach { it.isEnabled = live && !sending }
        autoReplyBox.isEnabled = live && currentAgent.isNotBlank()
        autoReplyBox.toolTipText = when {
            currentAgent.isBlank() -> "Set your agent id in ⚙ to use auto-reply."
            BoardSettings.get().state.autoReplyMayEdit -> "Let $currentAgent answer and work on messages addressed to it, by running Claude Code " +
                "in the background. It may edit, build and push (allowed in ⚙). Each answer spends Claude credits."
            else -> "Let $currentAgent answer messages addressed to it, by running Claude Code in the background. " +
                "It only reads and answers; allow code changes in ⚙. Each answer spends Claude credits."
        }
        senderLabel.text = if (live) "You post as \"$humanSender\"" else "Switch to HTTP or Live to message the agents."
    }

    /** Agents on the board, newest list each render; keeps the current pick when it is still there. */
    private fun updateRecipients(state: BoardState) {
        val agents = state.agents.keys.filter { it != humanSender }.sorted()
        val wanted = listOf(EVERYONE) + agents
        val current = (0 until toBox.itemCount).map { toBox.getItemAt(it) }
        if (wanted == current) return
        val selected = toBox.selectedItem as? String
        toBox.model = DefaultComboBoxModel(wanted.toTypedArray())
        toBox.selectedItem = if (selected in wanted) selected else currentAgent.takeIf { it in wanted } ?: EVERYONE
    }

    /** Hands the typed message to the service, which posts it off the UI thread and refreshes. */
    private fun send() {
        val text = messageField.text.trim()
        if (text.isEmpty() || sending || !snapshot.live) return
        val to = (toBox.selectedItem as? String)?.takeIf { it != EVERYONE }
        sending = true
        updateComposer()
        service.send(text, to, humanSender) { result ->
            sending = false
            updateComposer()
            result.fold(
                onSuccess = {
                    messageField.text = ""
                    showStatus("Sent to ${to ?: "everyone"}")
                },
                onFailure = { showError("Could not send: ${it.message ?: it.javaClass.simpleName}") },
            )
            messageField.requestFocusInWindow()
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
        updateRecipients(state)
        content.removeAll()

        content.add(header(state))

        state.openConflicts.forEach { c -> content.add(conflictBanner(c.claimant, c.currentOwner, c.overlappingPaths, c.taskId, c.reason)) }

        // The latest handoff is the moment the demo is about, so keep it above the fold.
        state.timeline.lastOrNull { it.event.type == EventType.HANDOFF }?.let { content.add(handoffCard(it, latest = true)) }

        // Newest first, so a message arriving during a live demo is visible without scrolling.
        // Automatic "[auto] got your message" acknowledgements are left out here: they are noise on stage.
        val recent = state.timeline.filterNot { it.isAuto() }.takeLast(RECENT_COUNT).asReversed()
        if (recent.isNotEmpty()) {
            content.add(sectionTitle("Recent", null))
            recent.forEach { p -> content.add(timelineRow(p, maxChars = RECENT_CHARS)) }
        }

        val agents = state.agents.values.sortedWith(compareBy({ it.agent != currentAgent }, { it.agent }))
        content.add(sectionTitle("Agents", agents.size))
        if (agents.isEmpty()) content.add(emptyNote("No agents yet. They appear when they post."))
        agents.forEach { a -> content.add(agentCard(state, a.agent, a.activeTasks, a.branch, a.lastSeen)) }

        val tasks = state.tasks.values.sortedBy { it.claimedAtCommentId }
        content.add(sectionTitle("Tasks", tasks.size))
        if (tasks.isEmpty()) content.add(emptyNote("No tasks yet. An agent claims one before it edits files."))
        tasks.forEach { t -> content.add(taskCard(t)) }

        content.add(sectionTitle("Timeline", state.timeline.size))
        if (state.timeline.isEmpty()) content.add(emptyNote("Nothing has happened yet."))
        state.timeline.forEach { p ->
            content.add(
                when {
                    p.event.type == EventType.HANDOFF -> handoffCard(p)
                    p.isAuto() -> autoRow(p)
                    else -> timelineRow(p)
                },
            )
        }

        content.revalidate()
        content.repaint()
        if (scrollToTopOnRender) {
            scrollToTopOnRender = false
            // After layout, so the new height is known.
            ApplicationManager.getApplication().invokeLater { scrollPane.verticalScrollBar.value = 0 }
        }
    }

    // ---- sections ----

    /** Title strip: name, live/demo pill, and a one-line summary a judge can read at a glance. */
    private fun header(state: BoardState): JComponent {
        val live = snapshot.live
        // The tool window already shows "Agent Board", so the strip leads with the state, not the name again.
        val pill: List<JComponent> = if (live) {
            listOf(StatusDot(BoardTheme.LIVE), gap(5), Chip("LIVE", BoardTheme.chipColors("task_claimed").first, BoardTheme.LIVE))
        } else {
            listOf(Chip("DEMO DATA", BoardTheme.chipColors("other").first, BoardTheme.MUTED))
        }
        val conflicts = state.openConflicts.size
        val summary = listOf(
            plural(state.agents.size, "agent"),
            plural(state.tasks.values.count { it.isActive }, "active task"),
            if (conflicts == 0) "no conflicts" else plural(conflicts, "conflict"),
        ).joinToString("  ·  ")
        val summaryLabel = plainLabel(summary).apply {
            foreground = if (conflicts > 0) BoardTheme.CONFLICT_TEXT else BoardTheme.MUTED
            font = JBFont.label().asBold()
        }
        return row(*(pill + listOf(gap(8), summaryLabel, glue())).toTypedArray()).apply {
            border = JBUI.Borders.empty(8, 12, 4, 12)
        }
    }

    private fun conflictBanner(claimant: String, owner: String, paths: List<String>, taskId: String?, reason: String): JComponent {
        val files = paths.map(::fileName).ifEmpty { listOfNotNull(taskId) }.joinToString()
        return wrap(RoundedCard(BoardTheme.CONFLICT_BG, BoardTheme.CONFLICT_LINE, 2f).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(9, 12)
            add(row(JBLabel(AllIcons.General.Warning), gap(6), plainLabel("Heads up: two agents want the same file").apply {
                font = JBFont.label().asBold()
                foreground = BoardTheme.CONFLICT_TEXT
            }))
            add(row(Avatar(claimant, 18), gap(5), nameLabel(claimant), gap(4), plainLabel("wants").muted(), gap(4),
                Chip(files, BoardTheme.chipColors("conflict").first, BoardTheme.chipColors("conflict").second, mono = true)).padTop(6))
            add(row(Avatar(owner, 18), gap(5), nameLabel(owner), gap(4), plainLabel("already owns it").muted()).padTop(4))
            add(text("They talk on the board before anyone edits.", BoardTheme.MUTED).apply {
                font = JBFont.small()
                border = JBUI.Borders.emptyTop(6)
            })
            toolTipText = reason  // the full path and task, without crowding the banner
        })
    }

    private fun handoffCard(p: PostedEvent, latest: Boolean = false): JComponent {
        val e = p.event
        val to = e.toAgent ?: "?"
        return wrap(RoundedCard(BoardTheme.HANDOFF_BG, BoardTheme.HANDOFF_LINE, if (latest) 2f else 1f).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(9, 12)
            add(row(JBLabel(AllIcons.Actions.Forward), gap(6),
                plainLabel(if (latest) "Work handed over" else "Handoff").apply { font = JBFont.label().asBold() },
                glue(), plainLabel(BoardTheme.ago(p.createdAt)).muted().small()))
            add(row(Avatar(e.agent, 18), gap(5), nameLabel(e.agent), gap(6), plainLabel("→").muted(), gap(6),
                Avatar(to, 18), gap(5), nameLabel(to)).padTop(6))
            e.summary?.takeIf { it.isNotBlank() }?.let { add(text(it).apply { border = JBUI.Borders.emptyTop(6) }) }
            val chips = mutableListOf<JComponent>()
            e.taskId?.let { chips += Chip(it, BoardTheme.chipColors("other").first, BoardTheme.chipColors("other").second) }
            e.branch?.let { chips += gap(5); chips += Chip(it, BoardTheme.chipColors("other").first, BoardTheme.MUTED, mono = true) }
            e.commit?.let { commit ->
                chips += gap(5)
                chips += Chip("commit $commit ↗", BoardTheme.chipColors("handoff").first, BoardTheme.chipColors("handoff").second, mono = true).apply {
                    toolTipText = "Open $commit on GitHub"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(ev: MouseEvent) = BrowserUtil.browse("https://github.com/$repo/commit/$commit")
                    })
                }
            }
            if (chips.isNotEmpty()) add(row(*chips.toTypedArray()).padTop(7))
        })
    }

    private fun agentCard(state: BoardState, agent: String, active: List<String>, branch: String?, lastSeen: String): JComponent {
        val mine = agent == currentAgent
        val card = RoundedCard(line = if (mine) BoardTheme.ACCENT else BoardTheme.CARD_LINE, lineWidth = if (mine) 2f else 1f).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 10)
            val tags = mutableListOf<JComponent>(Avatar(agent, 26), gap(8), nameLabel(agent))
            BoardTheme.providerOf(agent)?.let { tags += gap(6); tags += Chip(it, BoardTheme.chipColors("other").first, BoardTheme.chipColors("other").second) }
            if (mine) { tags += gap(4); tags += Chip("you", BoardTheme.chipColors("handoff").first, BoardTheme.ACCENT) }
            tags += glue()
            tags += plainLabel(BoardTheme.ago(lastSeen)).muted().small()
            add(row(*tags.toTypedArray()))
            val detail = if (active.isEmpty()) {
                row(plainLabel("Free, no task claimed").muted().small())
            } else {
                val parts = mutableListOf<JComponent>()
                active.forEachIndexed { i, t ->
                    if (i > 0) parts += gap(4)
                    parts += Chip(t, BoardTheme.chipColors("task_claimed").first, BoardTheme.chipColors("task_claimed").second)
                }
                branch?.let { parts += gap(6); parts += plainLabel(it).muted().small() }
                row(*parts.toTypedArray())
            }
            add(detail.indent(34).padTop(4))
            val accounts = accountsFor(state, agent)
            // The agent field is self-declared, so one agent posting from several accounts is worth flagging.
            if (accounts.size > 1) add(text("Posts via ${accounts.joinToString()}: more than one account", BoardTheme.CONFLICT_TEXT).apply {
                font = JBFont.small(); border = JBUI.Borders.empty(4, 34, 0, 0)
            })
        }
        return wrap(card)
    }

    private fun taskCard(t: TaskState): JComponent {
        val mine = t.owner == currentAgent
        val status = t.status.name.lowercase()
        val (sbg, sfg) = BoardTheme.statusChip(status)
        val card = RoundedCard(line = if (mine) BoardTheme.ACCENT else BoardTheme.CARD_LINE, lineWidth = if (mine) 2f else 1f).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 10)
            add(row(plainLabel(t.taskId).apply { font = JBFont.label().asBold() }, glue(), Chip(status, sbg, sfg)))
            val owner = t.owner
            add((if (owner != null) row(Avatar(owner, 16), gap(5), nameLabel(owner).small())
                else row(plainLabel("free for anyone").muted().small())).padTop(5))
            if (t.paths.isNotEmpty()) {
                val files = mutableListOf<JComponent>()
                t.paths.take(3).forEachIndexed { i, path ->
                    if (i > 0) files += gap(4)
                    files += Chip(fileName(path), BoardTheme.chipColors("other").first, BoardTheme.chipColors("other").second, mono = true).apply { toolTipText = path }
                }
                if (t.paths.size > 3) { files += gap(4); files += plainLabel("+${t.paths.size - 3}").muted().small() }
                add(row(*files.toTypedArray()).padTop(5))
            }
            t.lastMessage?.takeIf { it.isNotBlank() }?.let { add(text(it, BoardTheme.MUTED).apply { font = JBFont.small(); border = JBUI.Borders.emptyTop(5) }) }
        }
        return wrap(card)
    }

    /** One event. In "Recent" the text is cut to [maxChars] so one long message cannot fill the panel. */
    private fun timelineRow(p: PostedEvent, maxChars: Int = Int.MAX_VALUE): JComponent {
        val e = p.event
        val full = (e.message ?: e.summary ?: "").trim()
        val body = if (full.length <= maxChars) full else full.take(maxChars).substringBeforeLast(' ') + " …"
        val (cbg, cfg) = BoardTheme.chipColors(e.type)
        val head = mutableListOf<JComponent>(Avatar(e.agent, 20), gap(6), nameLabel(e.agent))
        e.toAgent?.let { head += gap(4); head += plainLabel("→").muted(); head += gap(4); head += nameLabel(it) }
        head += gap(6); head += Chip(BoardTheme.chipText(e.type), cbg, cfg)
        if (e.type != EventType.MESSAGE) e.taskId?.let { head += gap(4); head += plainLabel(it).muted().small() }
        head += glue(); head += plainLabel(BoardTheme.ago(p.createdAt) + via(p)).muted().small()
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 10, 4, 10)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(row(*head.toTypedArray()))
            if (body.isNotBlank()) add(text(body).apply { border = JBUI.Borders.empty(3, 26, 0, 0) })
        }.fillWidth()
    }

    /** "[auto] got your message" acknowledgements: one quiet line. */
    private fun autoRow(p: PostedEvent): JComponent = row(
        Avatar(p.event.agent, 14), gap(6),
        plainLabel("${p.event.agent} confirmed it got the message").muted().small(),
        glue(), plainLabel(BoardTheme.ago(p.createdAt)).muted().small(),
    ).apply { border = JBUI.Borders.empty(2, 13, 2, 10) }

    // ---- who really posted ----
    // `agent` is whatever the poster typed; `author` is the GitHub account that posted the comment.

    /** " · via login" when the GitHub account is known and differs from the agent name, else "". */
    private fun via(p: PostedEvent): String =
        p.author.takeIf { it.isNotBlank() && it != p.event.agent && it != "local" }?.let { " · via $it" } ?: ""

    private fun PostedEvent.isAuto(): Boolean = event.message?.trimStart()?.startsWith("[auto]") == true

    /** GitHub accounts that posted as [agent], in first-seen order. Fixtures and memory stores have none. */
    private fun accountsFor(state: BoardState, agent: String): List<String> =
        state.timeline.filter { it.event.agent == agent }
            .map { it.author }
            .filter { it.isNotBlank() && it != agent && it != "local" }
            .distinct()

    // ---- small building blocks ----

    /** Outer margin around a card, so cards breathe inside the narrow tool window. */
    private fun wrap(card: JComponent): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        add(card, BorderLayout.CENTER)
    }.fillWidth()

    /** A left-to-right line of components that keeps its own height inside the vertical layout. */
    private fun row(vararg parts: JComponent): JComponent = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        parts.forEach { add(it.apply { alignmentY = Component.CENTER_ALIGNMENT }) }
    }.also { it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height) }

    private fun gap(px: Int): JComponent = Box.createHorizontalStrut(JBUI.scale(px)) as JComponent
    private fun glue(): JComponent = Box.createHorizontalGlue() as JComponent

    private fun JComponent.padTop(px: Int): JComponent = apply { border = JBUI.Borders.emptyTop(px) }
    private fun JComponent.indent(px: Int): JComponent = apply { border = JBUI.Borders.emptyLeft(px) }
    private fun JBLabel.muted(): JBLabel = apply { foreground = BoardTheme.MUTED }
    private fun JBLabel.small(): JBLabel = apply { font = JBFont.small() }

    /** An agent or person name, in their color. */
    private fun nameLabel(agent: String): JBLabel = plainLabel(agent).apply {
        font = JBFont.label().asBold()
        foreground = BoardTheme.colorFor(agent)
    }

    private fun sectionTitle(title: String, count: Int?) = plainLabel(
        title.uppercase() + (count?.let { "  $it" } ?: ""),
    ).apply {
        font = JBFont.small().asBold()
        foreground = BoardTheme.MUTED
        border = JBUI.Borders.empty(14, 12, 4, 12)
    }

    private fun emptyNote(s: String) = text(s, BoardTheme.MUTED).apply {
        font = JBFont.small()
        border = JBUI.Borders.empty(2, 12, 4, 12)
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

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
        const val RECENT_COUNT = 3
        const val RECENT_CHARS = 180
        const val EVERYONE = "Everyone"
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
