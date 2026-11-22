package com.agentboard.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On project open: when the repo links a team board (RepoBoardLink) and this IDE is not set up yet,
 * offer it in one balloon, so a new teammate goes from "installed the plugin" to "on the board" in one click.
 */
class BoardStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val config = BoardConfig.current()
        if (config.httpAvailable && config.agent.isNotBlank()) return  // already set up
        val link = runCatching { RepoBoardLink.discover(project) }.getOrNull() ?: return
        withContext(Dispatchers.EDT) {
            NotificationGroupManager.getInstance().getNotificationGroup("BOB")
                .createNotification(
                    "Your team's board is linked in this repo",
                    "BOB found it in ${link.from}. Open the board and join with your name.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring("Open the board") {
                    ToolWindowManager.getInstance(project).getToolWindow("BOB")?.activate(null)
                })
                .notify(project)
        }
    }
}
