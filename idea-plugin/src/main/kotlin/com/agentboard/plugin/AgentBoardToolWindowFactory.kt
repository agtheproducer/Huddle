package com.agentboard.plugin

import com.agentboard.core.DemoFixtures
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory

/**
 * STARTER (verified to build). Julieta's agent replaces the label with the real board UI,
 * see docs/tasks/julieta.md.
 */
class AgentBoardToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val state = DemoFixtures.state(DemoFixtures.Stage.CONFLICT)
        val label = JBLabel("Tasks: ${state.tasks.size}, open conflicts: ${state.openConflicts.size}")
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
    }
}
