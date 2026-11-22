package com.agentboard.server

import com.agentboard.core.EventCodec
import com.agentboard.core.RawComment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the server accepts. Pure Kotlin, no Node, so every rule is unit-tested.
 *
 * The server never invents its own idea of an event: it asks the same [EventCodec]
 * the plugin, the MCP server and the CLI use, so anything stored here is something
 * every client can read back.
 */
object EventRules {
    /** Largest request body read off the wire. Past this the server stops reading and answers 413. */
    const val MAX_REQUEST_BYTES = 64 * 1024

    /** Largest `message` or `summary` inside one event. */
    const val MAX_TEXT_BYTES = 8 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The comment body inside a POST. Clients send `{"body": "<fenced event>"}`
     * (see `HttpBoardStore`); anything else is taken as the body itself, so a bare
     * event JSON or a fenced block posted as-is also works.
     */
    fun commentBody(request: String): String {
        val obj = runCatching { json.parseToJsonElement(request) }.getOrNull() as? JsonObject
        val body = obj?.get("body") as? JsonPrimitive
        return if (body != null && body.isString) body.content else request
    }

    /** Null when [body] holds a valid board event, otherwise the reason it is refused. */
    fun rejection(body: String): String? {
        val event = EventCodec.parse(RawComment(0, "", "", body))?.event
            ?: return "body is not a valid board event (need a JSON event with a known \"type\" and an \"agent\")"
        for ((field, text) in listOf("message" to event.message, "summary" to event.summary)) {
            if (text != null && text.encodeToByteArray().size > MAX_TEXT_BYTES) {
                return "\"$field\" is over ${MAX_TEXT_BYTES / 1024} KB"
            }
        }
        return null
    }

    /**
     * The `since` filter of `GET /api/events?since=<id>`: 0 (everything) when absent,
     * null when present but not a whole number.
     */
    fun since(url: String): Long? {
        val query = url.substringAfter('?', "")
        val raw = query.split('&').firstOrNull { it.startsWith("since=") }?.substringAfter('=')
            ?: return 0
        return raw.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** The path without its query string, for routing. */
    fun path(url: String): String = url.substringBefore('?')
}
