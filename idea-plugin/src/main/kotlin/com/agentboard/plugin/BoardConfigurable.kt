package com.agentboard.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Settings → Tools → Agent Board. Lets a teammate point an installed plugin at the board without
 * starting IntelliJ from a terminal. Empty fields fall back to the AGENT_BOARD_* environment variables.
 */
class BoardConfigurable : Configurable {

    private val repoField = JBTextField().apply { emptyText.text = "owner/name, e.g. agtheproducer/Huddle" }
    private val issueField = JBTextField().apply { emptyText.text = "Issue number, e.g. 2" }
    private val agentField = JBTextField().apply { emptyText.text = "your agent id, e.g. amit-claude" }
    private val tokenField = JBPasswordField()
    private val clearToken = JBCheckBox("Remove the saved token")

    override fun getDisplayName(): String = "Agent Board"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Repository:", repoField)
        .addLabeledComponent("Issue:", issueField)
        .addLabeledComponent("Your agent id:", agentField)
        .addLabeledComponent("GitHub token:", tokenField)
        .addComponentToRightColumn(clearToken)
        .addComponentToRightColumn(hint(
            "A fine-grained token with Issues: Read and write. It is kept in the IDE's password store " +
                "(the macOS Keychain on a Mac), never in a settings file.",
        ))
        .addComponentToRightColumn(hint(
            "Empty fields use the AGENT_BOARD_REPO, AGENT_BOARD_ISSUE, AGENT_BOARD_AGENT and GITHUB_TOKEN " +
                "environment variables instead.",
        ))
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    // A fixed body width makes Swing wrap the HTML instead of running it off the edge of the page.
    private fun hint(text: String) = JBLabel("<html><body style='width: ${JBUI.scale(380)}px'>$text</body></html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    override fun isModified(): Boolean {
        val s = BoardSettings.get().state
        return repoField.text.trim() != s.repo ||
            issueField.text.trim() != s.issue ||
            agentField.text.trim() != s.agent ||
            tokenField.password.isNotEmpty() ||
            clearToken.isSelected
    }

    override fun apply() {
        val s = BoardSettings.get().state
        s.repo = repoField.text.trim()
        s.issue = issueField.text.trim()
        s.agent = agentField.text.trim()

        val newToken = String(tokenField.password).trim().takeIf { it.isNotEmpty() }
        val removeToken = clearToken.isSelected && newToken == null
        if (newToken != null) s.tokenSaved = true
        if (removeToken) s.tokenSaved = false
        tokenField.text = ""
        clearToken.isSelected = false

        // The password safe can block, so write it off the UI thread, then tell open tool windows.
        ApplicationManager.getApplication().executeOnPooledThread {
            when {
                newToken != null -> BoardSettings.writeToken(newToken)
                removeToken -> BoardSettings.writeToken(null)
            }
            ApplicationManager.getApplication().messageBus.syncPublisher(BoardSettingsListener.TOPIC).settingsChanged()
        }
    }

    override fun reset() {
        val s = BoardSettings.get().state
        repoField.text = s.repo
        issueField.text = s.issue
        agentField.text = s.agent
        tokenField.text = ""
        tokenField.emptyText.text = if (s.tokenSaved) "saved — type a new one to replace it" else "not saved"
        clearToken.isSelected = false
        clearToken.isEnabled = s.tokenSaved
    }
}
