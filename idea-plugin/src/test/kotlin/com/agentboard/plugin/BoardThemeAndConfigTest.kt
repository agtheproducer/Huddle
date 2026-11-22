package com.agentboard.plugin

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sidebar's display rules and the settings that decide which boards are offered. */
class BoardThemeAndConfigTest {

    @Test
    fun `a person and their agents share a color`() {
        assertEquals(BoardTheme.colorFor("julieta"), BoardTheme.colorFor("julieta-claude"))
        assertEquals(BoardTheme.colorFor("amit-claude"), BoardTheme.colorFor("amit-junie"))
    }

    @Test
    fun `initials and AI provider come from the agent id`() {
        assertEquals("JC", BoardTheme.initials("julieta-claude"))
        assertEquals("A", BoardTheme.initials("amit"))
        assertEquals("Claude", BoardTheme.providerOf("amit-claude"))
        assertEquals("Junie", BoardTheme.providerOf("amit-junie"))
        assertNull(BoardTheme.providerOf("julieta"))
    }

    @Test
    fun `times read like a chat`() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        assertEquals("just now", BoardTheme.ago("2026-11-22T09:59:40Z", now))
        assertEquals("4 min ago", BoardTheme.ago("2026-11-22T09:56:00Z", now))
        assertEquals("2 h ago", BoardTheme.ago("2026-11-22T07:30:00Z", now))
        assertEquals("not a time", BoardTheme.ago("not a time", now))
    }

    private fun config(http: String = "", repo: String = "", issue: String = "", token: String? = null) =
        BoardConfig(repo = repo, issue = issue, agent = "julieta-claude", httpUrl = http, tokenInSettings = false, envToken = token)

    @Test
    fun `the HTTP board is offered first, then GitHub, after the demo stages`() {
        assertEquals(listOf("Start", "Conflict", "Handoff", "Resolved"), BoardService.namesFor(config()))
        assertEquals(listOf("Start", "Conflict", "Handoff", "Resolved", "HTTP", "Live"),
            BoardService.namesFor(config(http = "https://board.example", repo = "o/r", issue = "2", token = "t")))
    }

    @Test
    fun `the GitHub board needs a repo, a numeric issue and a token`() {
        assertTrue(config(repo = "o/r", issue = "2", token = "t").liveAvailable)
        assertFalse(config(repo = "o/r", issue = "two", token = "t").liveAvailable)
        assertFalse(config(repo = "o/r", issue = "2").liveAvailable)
        assertTrue(config(http = "https://board.example").httpAvailable)
    }
}
