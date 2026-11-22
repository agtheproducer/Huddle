package com.agentboard.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node.js request handler (also the Vercel function entry point).
 *
 * Routes:
 * - GET  /api/events[?since=<id>] — events as a JSON array (only ids > since, if given)
 * - POST /api/events              — validate, store and broadcast one event (201, 400 or 413)
 * - GET  /api/stream              — SSE stream: existing events, then every new one
 *
 * Every response allows any origin, so browser clients can call the board directly.
 */
@JsName("handler")
fun handler(req: dynamic, res: dynamic) {
    try {
        route(req, res)
    } catch (e: Throwable) {
        // One bad request must never take the whole board down.
        console.error("Request ${req.method} ${req.url} failed: ${e.message}")
        if (!(res.headersSent as Boolean)) sendError(res, 500, "internal error") else res.end()
    }
}

private fun route(req: dynamic, res: dynamic) {
    val method = req.method as String
    val url = req.url as String
    val path = EventRules.path(url)

    when {
        method == "OPTIONS" -> send(res, 204, "")
        method == "GET" && path == "/api/events" -> {
            val since = EventRules.since(url)
            if (since == null) sendError(res, 400, "\"since\" must be a non-negative whole number")
            else send(res, 200, Server.eventsJson(since))
        }
        method == "POST" && path == "/api/events" -> handlePostEvent(req, res)
        method == "GET" && path == "/api/stream" -> handleSseStream(req, res)
        else -> send(res, 404, "Not found: $url", "text/plain")
    }
}

private val corsHeaders = mapOf(
    "Access-Control-Allow-Origin" to "*",
    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" to "Content-Type",
)

/** Node wants a plain JS object for headers, not a Kotlin Map. */
private fun headers(values: Map<String, String>): dynamic {
    val obj: dynamic = js("({})")
    for ((k, v) in values) obj[k] = v
    return obj
}

private fun send(res: dynamic, status: Int, body: String, type: String = "application/json") {
    res.writeHead(status, headers(corsHeaders + ("Content-Type" to type)))
    res.end(body)
}

private fun sendError(res: dynamic, status: Int, message: String) =
    send(res, status, JsonObject(mapOf("error" to JsonPrimitive(message))).toString())

/**
 * POST /api/events: read the body (at most [EventRules.MAX_REQUEST_BYTES]), check it
 * holds a valid event, then store it.
 */
fun handlePostEvent(req: dynamic, res: dynamic) {
    val tooLarge = "request body is over ${EventRules.MAX_REQUEST_BYTES / 1024} KB"
    val declared = (req.headers["content-length"] as? String)?.toLongOrNull()
    if (declared != null && declared > EventRules.MAX_REQUEST_BYTES) {
        rejectOversize(req, res, tooLarge)
        return
    }

    val chunks = mutableListOf<dynamic>()
    var size = 0
    var overLimit = false
    req.on("data", { chunk: dynamic ->
        if (!overLimit) {
            size += chunk.length as Int
            if (size > EventRules.MAX_REQUEST_BYTES) {
                overLimit = true
                chunks.clear()
                rejectOversize(req, res, tooLarge)
            } else {
                chunks.add(chunk)
            }
        }
    })
    req.on("end", {
        if (!overLimit) {
            val raw = js("Buffer").concat(chunks.toTypedArray()).toString("utf8") as String
            val body = EventRules.commentBody(raw)
            val reason = EventRules.rejection(body)
            if (reason != null) sendError(res, 400, reason)
            else send(res, 201, Server.post(body))
        }
    })
}

/** Answers 413 and drops the connection once the answer is out, so no more bytes are read. */
private fun rejectOversize(req: dynamic, res: dynamic, message: String) {
    res.setHeader("Connection", "close")
    sendError(res, 413, message)
    res.on("finish", { req.destroy() })
}

/**
 * GET /api/stream — SSE stream for real-time event push.
 */
fun handleSseStream(req: dynamic, res: dynamic) {
    res.writeHead(200, headers(corsHeaders + mapOf(
        "Content-Type" to "text/event-stream",
        "Cache-Control" to "no-cache",
        "Connection" to "keep-alive",
    )))

    // Node sends each write straight away; there is no flush() on ServerResponse
    // (calling one crashed the process on Node 24).
    res.write(": connection established\n\n")

    val writeCallback: (String) -> Unit = { data: String ->
        try {
            res.write(data)
        } catch (e: Exception) {
            // Client disconnected
        }
    }

    // Send the existing events first; the callback then handles new ones.
    for (event in Server.registerSseCallback(writeCallback)) {
        res.write(event)
    }

    req.on("close", {
        Server.unregisterSseCallback(writeCallback)
    })
}
