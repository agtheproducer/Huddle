package com.agentboard.mcp

import com.agentboard.core.BoardClient
import com.agentboard.core.InMemoryBoardStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardToolsTest {
    private val store = InMemoryBoardStore()
    private val amit = BoardTools(BoardClient(store, "amit-claude"), "test")
    private val julieta = BoardTools(BoardClient(store, "julieta-claude"), "test")

    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `full story - claim, conflict, message, handoff, release`() {
        val first = julieta.claimTask(args("task_id" to "dashboard", "paths" to listOf("Dashboard.kt"), "branch" to "agent/dashboard"))
        assertTrue(first.bool("owned"))

        val second = amit.claimTask(args("task_id" to "dashboard-ui", "paths" to listOf("Dashboard.kt"), "branch" to "agent/ui"))
        assertFalse(second.bool("owned"))
        assertEquals("julieta-claude", second.getValue("conflict").jsonObject.str("owner"))
        assertEquals(1, amit.getBoard().getValue("open_conflicts").jsonArray.size)

        amit.sendMessage(args("message" to "What have you built?", "to_agent" to "julieta-claude"))
        val inbox = julieta.getMessages(null)
        assertTrue(inbox.getValue("messages").jsonArray.any { it.jsonObject.str("message") == "What have you built?" })

        val handoff = julieta.handoffTask(
            args("task_id" to "dashboard", "to_agent" to "amit-claude", "summary" to "Cards done", "branch" to "agent/dashboard", "commit" to "a91f2c7"),
        )
        assertTrue(handoff.bool("accepted"))
        assertEquals(0, amit.getBoard().getValue("open_conflicts").jsonArray.size)

        val release = amit.releaseTask(args("task_id" to "dashboard"))
        assertTrue(release.bool("released"))
    }

    @Test
    fun `messages since an id only return newer ones`() {
        amit.sendMessage(args("message" to "one", "to_agent" to "julieta-claude"))
        val firstRead = julieta.getMessages(null)
        val last = firstRead.getValue("last_comment_id").jsonPrimitive.long
        amit.sendMessage(args("message" to "two", "to_agent" to "julieta-claude"))
        val next = julieta.getMessages(args("since_id" to last)).getValue("messages").jsonArray
        assertEquals(listOf("two"), next.map { it.jsonObject.str("message") })
    }

    @Test
    fun `non-owner handoff is not accepted`() {
        julieta.claimTask(args("task_id" to "dashboard", "paths" to listOf("Dashboard.kt"), "branch" to "b"))
        val result = amit.handoffTask(args("task_id" to "dashboard", "to_agent" to "amit-claude", "summary" to "s", "branch" to "b", "commit" to "c"))
        assertFalse(result.bool("accepted"))
    }

    @Test
    fun `missing arguments are reported clearly`() {
        val error = assertFailsWith<ToolInputException> { amit.claimTask(args("task_id" to "x", "branch" to "b")) }
        assertTrue("paths" in error.message!!)
    }

    @Test
    fun `paths can be a comma separated string`() {
        val result = amit.claimTask(args("task_id" to "x", "paths" to "a.kt, b.kt", "branch" to "b"))
        assertTrue(result.bool("owned"))
        val task = amit.getBoard().getValue("tasks").jsonArray.first().jsonObject
        assertEquals(2, task.getValue("paths").jsonArray.size)
    }

    @Test
    fun `without env vars the server uses demo fixtures`() {
        val tools = createTools(emptyMap())
        val board = tools.getBoard()
        assertTrue(board.str("mode").startsWith("fixtures"))
        assertEquals("amit-claude", board.str("you"))
        assertTrue(board.getValue("tasks").jsonArray.isNotEmpty())
    }
}
