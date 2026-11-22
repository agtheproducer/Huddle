package com.agentboard.plugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Finding the team's board from the repo: a wrong answer here means a teammate lands on demo data. */
class RepoBoardLinkTest {
    @Test
    fun `reads the url from the link file`() {
        assertEquals("https://board.example.dev", RepoBoardLink.parseUrl("""{ "url": "https://board.example.dev/" }"""))
        assertNull(RepoBoardLink.parseUrl("""{ "url": "ftp://nope" }"""))
        assertNull(RepoBoardLink.parseUrl("not json"))
    }

    @Test
    fun `finds the GitHub repo of origin over https and ssh`() {
        assertEquals("agtheproducer/bob", repoOf("https://github.com/agtheproducer/bob.git"))
        assertEquals("agtheproducer/Huddle", repoOf("git@github.com:agtheproducer/Huddle.git"))
        assertEquals("team/app", repoOf("https://github.com/team/app"))
        assertNull(repoOf("https://gitlab.com/team/app.git"))
    }

    @Test
    fun `typed settings win over the repo link`() {
        val link = RepoBoardLink.Link("https://from-repo.dev", ".bob/board.json")
        val typed = BoardConfig("", "", "amit-claude", "http://localhost:3000", false, null)
        assertEquals("http://localhost:3000", typed.withRepoLink(link).httpUrl)
        val empty = typed.copy(httpUrl = "")
        assertEquals("https://from-repo.dev", empty.withRepoLink(link).httpUrl)
        assertEquals(".bob/board.json", empty.withRepoLink(link).httpFromRepo)
    }

    private fun repoOf(url: String): String? {
        val root = createTempDirectory("repo").toFile()
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("[core]\n\tbare = false\n[remote \"origin\"]\n\turl = $url\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n[branch \"main\"]\n")
        return RepoBoardLink.githubRepo(root)
    }
}
