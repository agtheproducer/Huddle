package com.agentboard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardReducerTest {

    private fun client(store: BoardStore, agent: String) = BoardClient(store, agent)

    @Test
    fun `two competing claims produce one owner and one conflict`() {
        val store = InMemoryBoardStore()
        val a = client(store, "agent-a").claimTask("dashboard", listOf("Dashboard.kt"), "agent/a")
        val b = client(store, "agent-b").claimTask("dashboard", listOf("Dashboard.kt"), "agent/b")

        assertTrue(a.owned)
        assertFalse(b.owned)
        assertNotNull(b.conflict)

        val board = client(store, "agent-a").board()
        assertEquals("agent-a", board.tasks.getValue("dashboard").owner)
        assertEquals(1, board.openConflicts.size)
    }

    @Test
    fun `claiming another task on an owned path is a conflict`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("dashboard", listOf("Dashboard.kt"), "agent/a")
        val b = client(store, "agent-b").claimTask("charts", listOf("Charts.kt", "Dashboard.kt"), "agent/b")

        assertFalse(b.owned)
        assertEquals(listOf("Dashboard.kt"), b.conflict?.overlappingPaths)
    }

    @Test
    fun `claimed folder conflicts with a file inside it`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("plugin", listOf("idea-plugin/src/"), "agent/a")
        val b = client(store, "agent-b").claimTask(
            "dashboard", listOf("idea-plugin/src/main/kotlin/Dashboard.kt"), "agent/b",
        )

        assertFalse(b.owned)
        assertEquals("agent-a", b.conflict?.currentOwner)
        assertEquals("plugin", client(store, "agent-b").board().ownerOfPath("idea-plugin/src/main/kotlin/Dashboard.kt")?.taskId)
    }

    @Test
    fun `file claim conflicts with a later containing folder claim`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("dashboard", listOf("idea-plugin/src/main/kotlin/Dashboard.kt"), "agent/a")
        val b = client(store, "agent-b").claimTask("plugin", listOf("idea-plugin/"), "agent/b")

        assertFalse(b.owned)
        assertEquals(listOf("idea-plugin/"), b.conflict?.overlappingPaths)
        assertEquals("dashboard", client(store, "agent-b").board().ownerOfPath("idea-plugin/")?.taskId)
    }

    @Test
    fun `folder prefix does not claim sibling names`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("source", listOf("idea-plugin/src/"), "agent/a")
        val b = client(store, "agent-b").claimTask("other", listOf("idea-plugin/src2/File.kt"), "agent/b")

        assertTrue(b.owned)
        assertNull(client(store, "agent-b").board().ownerOfPath("idea-plugin/src3/File.kt"))
    }

    @Test
    fun `paths are normalized before comparing`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("source", listOf("./idea-plugin\\src/"), "agent/a")
        val b = client(store, "agent-b").claimTask("dashboard", listOf("idea-plugin/src/main/Dashboard.kt"), "agent/b")

        assertFalse(b.owned)
        assertEquals("agent-a", b.conflict?.currentOwner)
    }

    @Test
    fun `handoff needs a commit and moves ownership`() {
        val store = InMemoryBoardStore()
        val a = client(store, "agent-a")
        val b = client(store, "agent-b")
        a.claimTask("dashboard", listOf("Dashboard.kt"), "agent/a")
        b.claimTask("dashboard", listOf("Dashboard.kt"), "agent/b")

        a.handoffTask("dashboard", "agent-b", "Cards done, charts left", "agent/a", "a91f2c7")

        val board = b.board()
        assertEquals("agent-b", board.tasks.getValue("dashboard").owner)
        assertTrue(board.openConflicts.isEmpty(), "conflict is resolved by the handoff")
        assertEquals(1, b.messages().count { it.event.type == EventType.HANDOFF })
    }

    @Test
    fun `only the owner can hand off or release`() {
        val store = InMemoryBoardStore()
        client(store, "agent-a").claimTask("dashboard", listOf("Dashboard.kt"), "agent/a")
        client(store, "agent-b").handoffTask("dashboard", "agent-b", "mine now", "agent/b", "abc1234")
        client(store, "agent-b").releaseTask("dashboard")

        val board = client(store, "agent-a").board()
        assertEquals("agent-a", board.tasks.getValue("dashboard").owner)
        assertEquals(2, board.rejected.size)
    }

    @Test
    fun `release frees the path for others`() {
        val store = InMemoryBoardStore()
        val a = client(store, "agent-a")
        a.claimTask("dashboard", listOf("Dashboard.kt"), "agent/a")
        a.releaseTask("dashboard")

        val b = client(store, "agent-b").claimTask("dashboard", listOf("Dashboard.kt"), "agent/b")
        assertTrue(b.owned)
    }

    @Test
    fun `malformed comments are ignored`() {
        val store = InMemoryBoardStore(
            listOf(
                RawComment(1, "t", "human", "Hey team, starting now!"),
                RawComment(2, "t", "x", "```json\n{ not json }\n```"),
                RawComment(3, "t", "x", """{"v":1,"type":"unknown_type","agent":"x"}"""),
            ),
        )
        val board = client(store, "agent-a").board()
        assertTrue(board.timeline.isEmpty())
        assertNull(board.tasks["dashboard"])
    }

    @Test
    fun `plain JSON and fenced JSON both parse`() {
        val plain = RawComment(1, "t", "a", """{"v":1,"type":"message","agent":"a","message":"hi"}""")
        val fenced = RawComment(2, "t", "a", "Note\n```json\n{\"v\":1,\"type\":\"message\",\"agent\":\"a\",\"message\":\"hi\"}\n```")
        assertNotNull(EventCodec.parse(plain))
        assertNotNull(EventCodec.parse(fenced))
    }

    @Test
    fun `demo fixtures follow the judges' story`() {
        val conflict = DemoFixtures.state(DemoFixtures.Stage.CONFLICT)
        assertEquals("julieta-claude", conflict.tasks.getValue("dashboard").owner)
        assertEquals(1, conflict.openConflicts.size)

        val resolved = DemoFixtures.state(DemoFixtures.Stage.RESOLVED)
        assertEquals("amit-claude", resolved.tasks.getValue("dashboard").owner)
        assertTrue(resolved.openConflicts.isEmpty())
        assertEquals(listOf("plugin-tests"), resolved.agents.getValue("julieta-claude").activeTasks)
    }
}
