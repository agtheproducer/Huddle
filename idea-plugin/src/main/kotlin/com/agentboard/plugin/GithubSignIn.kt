package com.agentboard.plugin

import com.agentboard.core.BrowserLogin
import com.agentboard.core.BoardSignIn
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.serialization.json.*
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

internal object GithubSignIn {
    // A board session belongs to this IDE run and board origin. Closing the IDE discards it.
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun session(board: String): String? = sessions[board.trimEnd('/')]

    fun currentLogin(raw: String): String? {
        val board = BoardSignIn.endpoint(raw)
        val credential = session(board) ?: return null
        val body = BoardSignIn.request("$board/api/auth/me", credential = credential)
        return Json.parseToJsonElement(body).jsonObject.getValue("login").jsonPrimitive.content
    }

    fun start(project: Project, raw: String, signedIn: (String) -> Unit) {
        val board = runCatching { BoardSignIn.endpoint(raw) }.getOrElse {
            Messages.showErrorDialog(project, it.message ?: "Invalid board URL", "GitHub Sign-In")
            return
        }
        object : Task.Backgroundable(project, "Signing in to BOB", true) {
            private var login = ""
            override fun run(indicator: ProgressIndicator) {
                val config = Json.parseToJsonElement(BoardSignIn.request("$board/api/auth/config")).jsonObject
                val client = config["client_id"]?.jsonPrimitive?.content.orEmpty()
                require(client.isNotBlank()) { "This board has no GitHub OAuth app configured." }
                val result = if (config["browser"]?.jsonPrimitive?.booleanOrNull == true) {
                    indicator.text = "Approve BOB in your browser"
                    BrowserLogin.signIn(board, client, { BrowserUtil.browse(it) }, indicator::checkCanceled)
                } else {
                    val credential = deviceLogin(client, indicator) ?: return
                    indicator.checkCanceled()
                    BoardSignIn.exchange(board, credential)
                }
                login = result.getValue("login").jsonPrimitive.content
                sessions[board] = result.getValue("session").jsonPrimitive.content
            }
            override fun onSuccess() {
                if (login.isNotEmpty() && !project.isDisposed) {
                    signedIn(login)
                    com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)?.toFront()
                }
            }
            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message ?: "Sign-in failed", "GitHub Sign-In")
            }
        }.queue()
    }

    private fun deviceLogin(client: String, indicator: ProgressIndicator): String? {
        val result = BoardSignIn.github("device/code", mapOf("client_id" to client, "scope" to ""))
        val code = result["user_code"]?.jsonPrimitive?.content ?: error("Check the OAuth app client ID and enable device flow.")
        val device = result.getValue("device_code").jsonPrimitive.content
        var interval = result.getValue("interval").jsonPrimitive.int.coerceAtLeast(1)
        val expires = System.nanoTime() + result.getValue("expires_in").jsonPrimitive.long * 1_000_000_000L
        var proceed = false
        SwingUtilities.invokeAndWait {
            proceed = JOptionPane.showConfirmDialog(null, "Enter $code on github.com/login/device.",
                "BOB · Open GitHub", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
            if (proceed) BrowserUtil.browse("https://github.com/login/device")
        }
        if (!proceed) return null
        indicator.text = "Enter $code on github.com/login/device"
        while (System.nanoTime() < expires) {
            repeat(interval) { indicator.checkCanceled(); Thread.sleep(1000) }
            val reply = BoardSignIn.github("oauth/access_token", mapOf("client_id" to client, "device_code" to device,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            reply["access_token"]?.let { return it.jsonPrimitive.content }
            when (reply["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> error("GitHub declined or expired the sign-in. Try again.")
            }
        }
        error("GitHub sign-in expired.")
    }
}
