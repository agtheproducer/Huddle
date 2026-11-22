package com.agentboard.plugin

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * One chat line: a bubble that hugs its text up to [MAX_SHARE] of the panel width, on the right for
 * your own messages and on the left (after an avatar column) for everyone else's.
 *
 * Swing's box layouts give a wrapping text area either its one-line width or the full width; chat needs
 * "as wide as the text, never wider than most of the panel", so this row lays its bubble out itself.
 */
class BubbleRow(
    private val avatar: JComponent?,
    private val bubble: JComponent,
    private val body: JTextArea,
    private val mine: Boolean,
    topGap: Int,
) : JPanel(null) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(topGap, 10, 1, 10)
        avatar?.let { add(it) }
        add(bubble)
    }

    /** Others' bubbles always start after the avatar column, so a sender's grouped messages line up. */
    private val leadWidth: Int get() = if (mine) 0 else JBUI.scale(AVATAR_COLUMN)

    private fun available(total: Int) = total - insets.left - insets.right - leadWidth

    private fun bubbleWidth(total: Int): Int {
        val max = (available(total) * MAX_SHARE).toInt()
        val fm = body.getFontMetrics(body.font)
        val textWidth = body.text.lines().maxOfOrNull { fm.stringWidth(it) } ?: 0
        val others = (0 until bubble.componentCount).map { bubble.getComponent(it) }
            .filter { it !== body }.maxOfOrNull { it.preferredSize.width } ?: 0
        val natural = maxOf(textWidth + JBUI.scale(4), others) + bubble.insets.left + bubble.insets.right
        return natural.coerceIn(JBUI.scale(64), max.coerceAtLeast(JBUI.scale(64)))
    }

    private fun bubbleHeight(width: Int): Int {
        body.setSize(width - bubble.insets.left - bubble.insets.right, Short.MAX_VALUE.toInt())
        return bubble.preferredSize.height
    }

    private fun totalWidth(): Int = width.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: JBUI.scale(320)

    override fun doLayout() {
        val w = bubbleWidth(totalWidth())
        val h = bubbleHeight(w)
        val x = if (mine) width - insets.right - w else insets.left + leadWidth
        bubble.setBounds(x, insets.top, w, h)
        avatar?.let {
            val d = it.preferredSize
            it.setBounds(insets.left, insets.top, d.width, d.height)
        }
    }

    override fun getPreferredSize(): Dimension {
        val total = totalWidth()
        return Dimension(total, bubbleHeight(bubbleWidth(total)) + insets.top + insets.bottom)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    private companion object {
        const val MAX_SHARE = 0.84
        const val AVATAR_COLUMN = 30
    }
}
