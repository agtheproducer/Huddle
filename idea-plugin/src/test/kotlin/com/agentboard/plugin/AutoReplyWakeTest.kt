package com.agentboard.plugin

import com.agentboard.core.BoardClient
import com.agentboard.core.InMemoryBoardStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which board events wake an agent's auto-reply. Wrong answers here mean silent agents or reply loops. */
class AutoReplyWakeTest {
    private val store = InMemoryBoardStore()
    private fun client(agent: String) = BoardClient(store, agent)
    private fun woken(agent: String = "julieta-claude", after: Long = 0) =
        AutoReply.wakeEvents(client(agent).board(), agent, after).map { it.event.message }

    @Test
    fun `a verified second agent receives its own batch in board order`() {
        val codex = "julieta-codex"
        val claude = "julieta-claude"
        client(codex).sendMessage("Joined")
        client("amit").sendMessage("First", toAgent = codex)
        client("amit").sendMessage("Second", toAgent = "julieta")
        client("amit").sendMessage("Third", toAgent = codex)
        val raw = client(claude).board()
        val board = raw.copy(timeline = raw.timeline.map {
            if (it.event.agent == codex) it.copy(verifiedLogin = "Julieta-LL") else it
        })
        val first = AutoReply.routedEvents(board, claude, 0, "julieta-ll").single()
        assertEquals("First", first.event.message)
        val second = AutoReply.routedEvents(board, claude, first.commentId, "julieta-ll").single()
        assertEquals("Second", second.event.message)
        assertEquals("Third", AutoReply.routedEvents(board, claude, second.commentId, "julieta-ll").single().event.message)
        assertEquals(listOf(second), AutoReply.routedEvents(board, claude, 0, "someone-else"))
        assertEquals(listOf(second), AutoReply.routedEvents(board, claude, 0, null))
        assertEquals(false, ownsAgent(codex, claude, "someone-else", board.verifiedOwners()))
        assertEquals(true, ownsAgent(codex, claude, "julieta-ll", board.verifiedOwners()))
        assertEquals(true, AutoReply.isCodex(first.event.toAgent!!))
    }

    @Test
    fun `wakes for messages addressed to the agent`() {
        client("amit-claude").sendMessage("What did you build?", toAgent = "julieta-claude")
        assertEquals(listOf("What did you build?"), woken())
    }

    @Test
    fun `wakes for work addressed to the agent's human`() {
        client("amit-claude").sendMessage("Julieta, please claim calc-docs", toAgent = "julieta")
        assertEquals(listOf("Julieta, please claim calc-docs"), woken())
    }

    @Test
    fun `the human writing to their own agent wakes it`() {
        client("julieta").sendMessage("Claim calc-docs", toAgent = "julieta-claude")
        assertEquals(listOf("Claim calc-docs"), woken())
    }

    @Test
    fun `never wakes for its own posts, broadcasts, other agents' mail or auto acknowledgements`() {
        client("julieta-claude").sendMessage("talking to myself", toAgent = "julieta-claude")
        client("amit-claude").sendMessage("hi everyone")
        client("amit-claude").sendMessage("for codex", toAgent = "codex")
        client("amit-claude").sendMessage("[auto] amit-claude got your message and is on it.", toAgent = "julieta-claude")
        client("julieta").sendMessage("note to self", toAgent = "julieta")
        assertEquals(emptyList(), woken())
    }

    @Test
    fun `only events after the last handled id`() {
        client("amit-claude").sendMessage("old", toAgent = "julieta-claude")
        val seen = client("julieta-claude").board().lastCommentId
        client("amit-claude").sendMessage("new", toAgent = "julieta-claude")
        assertEquals(listOf("new"), woken(after = seen))
    }

    @Test
    fun `the human behind an agent id`() {
        assertEquals("amit", AutoReply.humanOf("amit-claude"))
        assertEquals("julieta", AutoReply.humanOf("julieta"))
        assertEquals("human", AutoReply.humanOf(""))
    }
}
