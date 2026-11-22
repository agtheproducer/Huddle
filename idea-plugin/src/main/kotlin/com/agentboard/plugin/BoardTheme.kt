package com.agentboard.plugin

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.math.absoluteValue

/**
 * The Agent Board look: every color the sidebar uses, as light/dark pairs, plus the small
 * painted building blocks (avatar, chip, rounded card). One place for a designer's palette.
 */
object BoardTheme {
    // ---- semantic colors ----
    val ACCENT = JBColor(Color(0x3574F0), Color(0x548AF7))
    val LIVE = JBColor(Color(0x208A3C), Color(0x5FB865))
    val MUTED = JBColor(Color(0x6C707E), Color(0x9DA0A8))
    val CARD_BG = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
    val CARD_LINE = JBColor(Color(0xE4E6EB), Color(0x393B40))

    val CONFLICT_BG = JBColor(Color(0xFFF4E6), Color(0x45321F))
    val CONFLICT_LINE = JBColor(Color(0xF0A04B), Color(0xC97A2B))
    val CONFLICT_TEXT = JBColor(Color(0x9A4F07), Color(0xF2B46B))

    val HANDOFF_BG = JBColor(Color(0xEDF3FF), Color(0x25324D))
    val HANDOFF_LINE = JBColor(Color(0xA9C3F7), Color(0x3E5A92))

    /** Chip colors per event type: (background, text). */
    private val CLAIM = JBColor(Color(0xE6F4EA), Color(0x29402F)) to JBColor(Color(0x1E7B34), Color(0x8BD49B))
    private val MESSAGE = JBColor(Color(0xEEF0F4), Color(0x34363B)) to JBColor(Color(0x4A4E59), Color(0xC3C6CE))
    private val HANDOFF = JBColor(Color(0xE3ECFF), Color(0x2A3A5C)) to JBColor(Color(0x2F5FD0), Color(0x9DBCFF))
    private val CONFLICT = JBColor(Color(0xFFE9D2), Color(0x4D3520)) to JBColor(Color(0xA4530A), Color(0xF4B36A))
    private val DONE = JBColor(Color(0xEDE7FA), Color(0x3A3150)) to JBColor(Color(0x6A43C2), Color(0xC3A8FF))
    private val NEUTRAL = JBColor(Color(0xEEF0F4), Color(0x34363B)) to JBColor(Color(0x6C707E), Color(0xA7AAB2))

    /** Person colors: an agent shares its person's color (julieta and julieta-claude match). */
    private val PEOPLE = listOf(
        JBColor(Color(0x3574F0), Color(0x548AF7)), // blue
        JBColor(Color(0xD6457C), Color(0xE66E9A)), // pink
        JBColor(Color(0x1E8E6E), Color(0x3FB68E)), // green
        JBColor(Color(0xB65F0E), Color(0xE08A3A)), // amber
        JBColor(Color(0x7A4CD6), Color(0x9F7AF0)), // violet
        JBColor(Color(0x0F8EA8), Color(0x3DB4CC)), // teal
    )

    fun personOf(agent: String) = agent.substringBefore('-').lowercase()

    fun colorFor(agent: String): Color = PEOPLE[personOf(agent).hashCode().absoluteValue % PEOPLE.size]

    /** "julieta-claude" → "JC", "amit" → "A". */
    fun initials(agent: String): String =
        agent.split('-', '_', ' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

    /** Which AI an agent id names, for its tag; null for a person. */
    fun providerOf(agent: String): String? = when {
        agent.endsWith("claude") -> "Claude"
        agent.endsWith("junie") -> "Junie"
        agent.endsWith("codex") -> "Codex"
        agent.startsWith("test") -> "Test"
        else -> null
    }

    fun chipColors(type: String): Pair<Color, Color> = when (type) {
        "task_claimed" -> CLAIM
        "message" -> MESSAGE
        "handoff" -> HANDOFF
        "conflict" -> CONFLICT
        "task_done", "task_released" -> DONE
        else -> NEUTRAL
    }

    fun chipText(type: String): String = when (type) {
        "task_claimed" -> "claimed"
        "task_released" -> "released"
        "task_done" -> "done"
        "progress" -> "progress"
        else -> type
    }

    val statusChip: (String) -> Pair<Color, Color> = { status ->
        when (status) {
            "claimed" -> CLAIM
            "done" -> DONE
            else -> NEUTRAL
        }
    }

    /** "just now", "4 min ago", "2 h ago", else the clock time. */
    fun ago(iso: String, now: Instant = Instant.now()): String {
        val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso.ifBlank { "–" }
        val s = Duration.between(t, now).seconds
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 6 * 3600 -> "${s / 3600} h ago"
            else -> CLOCK.format(t)
        }
    }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun antialias(g: Graphics): Graphics2D = (g.create() as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
}

/** A round colored badge with initials. */
class Avatar(private val agent: String, private val diameter: Int = 22) : JComponent() {
    init {
        val d = JBUI.scale(diameter)
        preferredSize = Dimension(d, d)
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = agent
        alignmentY = Component.CENTER_ALIGNMENT
    }

    override fun paintComponent(g: Graphics) {
        val g2 = BoardTheme.antialias(g)
        val d = minOf(width, height)
        g2.color = BoardTheme.colorFor(agent)
        g2.fillOval(0, 0, d, d)
        val text = BoardTheme.initials(agent)
        g2.font = JBFont.label().asBold().deriveFont(d * if (text.length > 1) 0.40f else 0.48f)
        g2.color = Color.WHITE
        val fm = g2.fontMetrics
        g2.drawString(text, (d - fm.stringWidth(text)) / 2, (d - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

/** A small rounded pill with text. Plain text only: board content is never rendered as HTML. */
class Chip(text: String, private val bg: Color, fg: Color, mono: Boolean = false) : JBLabel(text) {
    init {
        putClientProperty("html.disable", true)
        foreground = fg
        font = if (mono) JBUI.Fonts.create("JetBrains Mono", JBFont.small().size).asBold() else JBFont.small().asBold()
        border = JBUI.Borders.empty(1, 7)
        horizontalAlignment = SwingConstants.CENTER
        isOpaque = false
        alignmentY = Component.CENTER_ALIGNMENT
    }

    override fun paintComponent(g: Graphics) {
        val g2 = BoardTheme.antialias(g)
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, height, height)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A rounded card: soft fill, thin outline, optional thicker outline for "this is mine". */
class RoundedCard(
    private val fill: Color = BoardTheme.CARD_BG,
    private val line: Color = BoardTheme.CARD_LINE,
    private val lineWidth: Float = 1f,
) : JBPanel<RoundedCard>() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = BoardTheme.antialias(g)
        val arc = JBUI.scale(12)
        val inset = JBUI.scale(1)
        g2.color = fill
        g2.fillRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, arc, arc)
        g2.color = line
        g2.stroke = BasicStroke(JBUI.scale(lineWidth.toInt().coerceAtLeast(1)).toFloat())
        g2.drawRoundRect(inset, inset, width - 2 * inset - 1, height - 2 * inset - 1, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A dot that marks a live connection. */
class StatusDot(private val color: Color) : JComponent() {
    init {
        val d = JBUI.scale(8)
        preferredSize = Dimension(d, d)
        maximumSize = preferredSize
        alignmentY = Component.CENTER_ALIGNMENT
    }

    override fun paintComponent(g: Graphics) {
        val g2 = BoardTheme.antialias(g)
        g2.color = color
        val d = minOf(width, height)
        g2.fillOval(0, (height - d) / 2, d, d)
        g2.dispose()
    }
}
