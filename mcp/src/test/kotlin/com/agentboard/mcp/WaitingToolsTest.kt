package com.agentboard.mcp

import com.agentboard.core.BoardClient
import com.agentboard.core.InMemoryBoardStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ask_agent and wait_for_message, with a fake clock so nothing really sleeps. */
class WaitingToolsTest {
    private val store = InMemoryBoardStore()
    private var now = 0L
    private var sleeps = 0
    /** Runs on each poll sleep: lets a test make "the other agent" post while we wait. */
    private var onSleep: (Int) -> Unit = {}

    private fun tools(agent: String) = BoardTools(
        BoardClient(store, agent), "test",
        maxWaitMillis = 45_000, pollMillis = 2_000,
        clock = { now },
        sleep = { ms -> now += ms; sleeps++; onSleep(sleeps) },
    )

    private val julieta = tools("julieta-claude")
    private val amit = tools("amit-claude")

    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.messages(key: String) = getValue(key).jsonArray.map { it.jsonObject.getValue("message").jsonPrimitive.content }

    @Test
    fun `ask_agent returns the reply posted while waiting`() {
        onSleep = { n -> if (n == 3) amit.sendMessage(args("message" to "Cards are done, take it", "to_agent" to "julieta-claude")) }

        val result = julieta.askAgent(args("to_agent" to "amit-claude", "question" to "What have you built?"))

        assertTrue(result.bool("answered"))
        assertEquals(listOf("Cards are done, take it"), result.messages("replies"))
        assertEquals(3, sleeps)
    }

    @Test
    fun `ask_agent ignores other agents and times out with a hint`() {
        onSleep = { n -> if (n == 1) tools("codex").sendMessage(args("message" to "unrelated", "to_agent" to "julieta-claude")) }

        val result = julieta.askAgent(args("to_agent" to "amit-claude", "question" to "Anyone there?"))

        assertFalse(result.bool("answered"))
        assertTrue(result.getValue("hint").jsonPrimitive.content.contains("wait_for_message"))
        assertEquals(45_000, now)
    }

    @Test
    fun `wait_for_message skips old history and returns only what is new`() {
        amit.sendMessage(args("message" to "old news", "to_agent" to "julieta-claude"))
        julieta.getBoard()  // first use: the cursor starts at the current end of the board
        onSleep = { n -> if (n == 2) amit.sendMessage(args("message" to "new question", "to_agent" to "julieta-claude")) }

        val first = julieta.waitForMessage(null)
        assertEquals(listOf("new question"), first.messages("messages"))
        assertFalse(first.bool("timed_out"))

        // The cursor moved past it, so the next wait does not return it again.
        onSleep = {}
        val second = julieta.waitForMessage(args("timeout_seconds" to 4))
        assertTrue(second.bool("timed_out"))
        assertTrue(second.getValue("messages").jsonArray.isEmpty())
    }

    @Test
    fun `waiting never blocks longer than the limit and own messages do not count`() {
        onSleep = { n -> if (n == 1) julieta.sendMessage(args("message" to "talking to myself", "to_agent" to "amit-claude")) }

        val result = julieta.waitForMessage(args("timeout_seconds" to 600))

        assertTrue(result.bool("timed_out"))
        assertEquals(45_000, now)
    }

    @Test
    fun `ask_agent skips the auto acknowledgement and returns the real answer`() {
        onSleep = { n ->
            if (n == 1) amit.sendMessage(args("message" to "[auto] amit-claude got your message and is on it.", "to_agent" to "julieta-claude"))
            if (n == 4) amit.sendMessage(args("message" to "Blocked on a permission prompt", "to_agent" to "julieta-claude"))
        }

        val result = julieta.askAgent(args("to_agent" to "amit-claude", "question" to "What is blocking the push?"))

        assertTrue(result.bool("answered"))
        assertEquals(listOf("Blocked on a permission prompt"), result.messages("replies"))
    }

    @Test
    fun `asking yourself is refused`() {
        assertFailsWith<ToolInputException> { julieta.askAgent(args("to_agent" to "julieta-claude", "question" to "hi")) }
    }
}
