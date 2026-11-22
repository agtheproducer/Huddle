package com.agentboard.server

import com.agentboard.core.BoardEvent
import com.agentboard.core.EventCodec
import com.agentboard.core.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRulesTest {
    private val valid = EventCodec.toCommentBody(BoardEvent(type = EventType.MESSAGE, agent = "a", message = "hi"))

    @Test fun acceptsTheShapeHttpBoardStoreSends() {
        val request = """{"body": ${kotlinx.serialization.json.JsonPrimitive(valid)}}"""
        val body = EventRules.commentBody(request)
        assertEquals(valid, body)
        assertNull(EventRules.rejection(body))
    }

    @Test fun acceptsABareEventPostedAsIs() {
        val bare = """{"type":"progress","agent":"a","message":"x"}"""
        assertNull(EventRules.rejection(EventRules.commentBody(bare)))
    }

    @Test fun rejectsNonEvents() {
        assertNotNull(EventRules.rejection("hello"))
        assertNotNull(EventRules.rejection(EventRules.commentBody("""{"body":"test event"}""")))
        assertNotNull(EventRules.rejection("""{"type":"nope","agent":"a"}"""))
        assertNotNull(EventRules.rejection("""{"type":"message","agent":""}"""))
    }

    @Test fun rejectsLongMessageOrSummary() {
        val long = "x".repeat(EventRules.MAX_TEXT_BYTES + 1)
        val msg = EventCodec.toCommentBody(BoardEvent(type = EventType.MESSAGE, agent = "a", message = long))
        val sum = EventCodec.toCommentBody(BoardEvent(type = EventType.HANDOFF, agent = "a", summary = long))
        assertTrue(EventRules.rejection(msg)!!.contains("message"))
        assertTrue(EventRules.rejection(sum)!!.contains("summary"))
        val atLimit = "x".repeat(EventRules.MAX_TEXT_BYTES)
        assertNull(EventRules.rejection(EventCodec.toCommentBody(BoardEvent(type = EventType.MESSAGE, agent = "a", message = atLimit))))
    }

    @Test fun parsesSince() {
        assertEquals(0L, EventRules.since("/api/events"))
        assertEquals(5L, EventRules.since("/api/events?since=5"))
        assertEquals(7L, EventRules.since("/api/events?x=1&since=7"))
        assertNull(EventRules.since("/api/events?since=abc"))
        assertNull(EventRules.since("/api/events?since=-1"))
        assertEquals("/api/events", EventRules.path("/api/events?since=5"))
    }
}

class BoardTest {
    private val fs = js("require('fs')")
    private val os = js("require('os')")
    private val event = EventCodec.toCommentBody(BoardEvent(type = EventType.MESSAGE, agent = "a", message = "hi"))

    private fun tempPath(): String =
        "${fs.mkdtempSync(os.tmpdir() + "/board-test-")}/sub/board.json"

    @Test fun sinceReturnsOnlyNewerEvents() {
        val board = Board(null)
        repeat(3) { board.post(event) }
        assertEquals(listOf(2L, 3L), board.events(since = 1).map { it.id })
        assertEquals(3, board.events().size)
        assertEquals(0, board.events(since = 3).size)
    }

    @Test fun eventsSurviveARestartAndIdsKeepIncreasing() {
        val path = tempPath()
        Board(EventFile(path)).apply { post(event); post(event) }
        val restarted = Board(EventFile(path))
        assertEquals(listOf(1L, 2L), restarted.events().map { it.id })
        restarted.post(event)
        assertEquals(3L, Board(EventFile(path)).events().last().id)
    }

    @Test fun corruptFileIsMovedAsideAndBoardStartsEmpty() {
        val path = tempPath()
        Board(EventFile(path)).post(event) // creates the folder
        fs.writeFileSync(path, "{not json")
        assertEquals(0, Board(EventFile(path)).events().size)
        val dir = path.substringBeforeLast('/')
        val names = (fs.readdirSync(dir) as Array<String>).toList()
        assertTrue(names.any { it.startsWith("board.json.corrupt-") }, "moved aside: $names")
    }

    @Test fun emptyOrMissingFileStartsEmpty() {
        val path = tempPath()
        assertEquals(0, Board(EventFile(path)).events().size)
        Board(EventFile(path)).post(event)
        fs.writeFileSync(path, "")
        assertEquals(0, Board(EventFile(path)).events().size)
    }
}
