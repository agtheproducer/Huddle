package com.agentboard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Incremental reads against a fake server: only new events after the first read, full resync now and then. */
class HttpBoardStoreTest {
    private val server = mutableListOf<RawComment>()
    private val urls = mutableListOf<String>()

    private fun event(id: Long) = RawComment(id, "2026-11-22T10:00:0${id % 10}Z", "local", "e$id")

    private fun json(list: List<RawComment>) =
        list.joinToString(",", "[", "]") { """{"id":${it.id},"createdAt":"${it.createdAt}","author":"local","body":"${it.body}"}""" }

    private val store = HttpBoardStore(
        "http://board", fullEvery = 3,
        get = { url ->
            urls += url
            val since = url.substringAfter("since=", "").toLongOrNull() ?: 0
            json(server.filter { it.id > since })
        },
        post = { _, _ -> error("not used") },
    )

    @Test
    fun `reads only new events after the first one and resyncs fully every few reads`() {
        server += listOf(event(1), event(2))
        assertEquals(listOf(1L, 2L), store.fetchComments().map { it.id })
        server += event(3)
        assertEquals(listOf(1L, 2L, 3L), store.fetchComments().map { it.id })
        assertEquals(listOf(1L, 2L, 3L), store.fetchComments().map { it.id })
        assertEquals(listOf("http://board/api/events", "http://board/api/events?since=2", "http://board/api/events?since=3"), urls)
    }

    @Test
    fun `a wiped board is noticed at the next full read`() {
        server += listOf(event(1), event(2), event(3))
        store.fetchComments()
        server.clear(); server += event(1)
        store.fetchComments(); store.fetchComments()  // incremental: still the old cache
        assertEquals(listOf(1L), store.fetchComments().map { it.id })  // full read every 3rd
    }

    @Test
    fun `a reply that is not the event list is an error, not an empty board`() {
        val broken = HttpBoardStore("http://board", get = { "<html>proxy error</html>" }, post = { _, _ -> "" })
        assertFailsWith<IllegalStateException> { broken.fetchComments() }
    }
}
