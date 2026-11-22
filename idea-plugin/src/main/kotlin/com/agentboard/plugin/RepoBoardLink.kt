package com.agentboard.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Zero-setup: a team publishes its board's address in the repo, in [LINK_FILE]
 * (`{"url": "https://…"}`), and every teammate's plugin finds it with no typing.
 *
 * Looked up in order: the file on GitHub's default branch (so a moved board reaches everyone the
 * moment the file is pushed, without a pull), then the file in the local checkout (offline, or a
 * repo that is not on GitHub). A URL typed in Settings always wins over both. Call from a background thread.
 */
object RepoBoardLink {
    const val LINK_FILE = ".bob/board.json"

    /** Where the address came from, for the sidebar's status line. */
    data class Link(val url: String, val from: String)

    private val LOG = Logger.getInstance(RepoBoardLink::class.java)
    private val URL_FIELD = Regex("\"url\"\\s*:\\s*\"(https?://[^\"\\s]+)\"")

    fun discover(project: Project): Link? {
        val root = project.basePath?.let(::File) ?: return null
        githubRepo(root)?.let { repo ->
            runCatching { IdeHttp.get("https://raw.githubusercontent.com/$repo/HEAD/$LINK_FILE") }
                .onFailure { LOG.info("No board link on GitHub for $repo: ${it.message}") }
                .getOrNull()
                ?.let(::parseUrl)
                ?.let { return Link(it, "github.com/$repo") }
        }
        val local = File(root, LINK_FILE)
        if (local.isFile) parseUrl(local.readText())?.let { return Link(it, LINK_FILE) }
        return null
    }

    /** `owner/name` of the checkout's `origin` remote when it is on GitHub, read from `.git/config`. */
    fun githubRepo(root: File): String? {
        val config = File(root, ".git/config").takeIf { it.isFile }?.readText() ?: return null
        val origin = config.substringAfter("[remote \"origin\"]", "").substringBefore("\n[")
        val url = Regex("url\\s*=\\s*(\\S+)").find(origin)?.groupValues?.get(1) ?: return null
        return Regex("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$").find(url)
            ?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
    }

    /**
     * A first name to suggest as the agent id: git's user.name ("Amit Grinman" → "amit"), else the
     * OS account name. Only a suggestion: the person confirms it in the sidebar.
     */
    fun suggestedName(root: File?): String {
        val gitName = runCatching {
            val p = ProcessBuilder("git", "config", "user.name").apply { root?.let(::directory) }
                .redirectErrorStream(true).start()
            if (p.waitFor(3, TimeUnit.SECONDS)) p.inputStream.bufferedReader().readText().trim() else ""
        }.getOrDefault("")
        val raw = gitName.ifBlank { System.getProperty("user.name").orEmpty() }
        return raw.substringBefore(' ').lowercase().filter { it.isLetterOrDigit() }.take(20)
    }

    internal fun parseUrl(json: String): String? = URL_FIELD.find(json)?.groupValues?.get(1)?.trimEnd('/')
}
