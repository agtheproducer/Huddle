package com.agentboard.server

/**
 * Vercel-compatible request handler using Node.js HTTP module.
 *
 * Routes:
 * - GET  /api/events  — return all events as JSON array
 * - POST /api/events  — post a new event, return the created comment
 * - GET  /api/stream  — SSE stream for real-time event push
 */

/**
 * Main entry point — exported for Vercel serverless function.
 *
 * This uses the Node.js http module pattern: the handler receives
 * req and res objects from Node.js http.createServer().
 */
@JsName("handler")
fun handler(req: dynamic, res: dynamic) {
    val method = req.method as String
    val url = req.url as String
    
    // Route matching
    when {
        method == "GET" && url == "/api/events" -> {
            val events = Server.getEvents()
            res.writeHead(200, mapOf("Content-Type" to "application/json"))
            res.end(events)
        }
        method == "POST" && url == "/api/events" -> {
            handlePostEvent(req, res)
        }
        method == "GET" && url == "/api/stream" -> {
            handleSseStream(req, res)
        }
        else -> {
            res.writeHead(404, mapOf("Content-Type" to "text/plain"))
            res.end("Not found: $url")
        }
    }
}

/**
 * Handle POST /api/events — collect body chunks and post the event.
 * Uses js() interop for the async Node.js event emitter pattern.
 */
fun handlePostEvent(req: dynamic, res: dynamic) {
    // Pass the Kotlin function as a JS parameter so it's accessible in the closure
    val postFn: (String) -> String = { body: String -> Server.postEvent(body) }
    js("""
        (function(postFn) {
            var chunks = [];
            req.on('data', function(chunk) {
                chunks.push(chunk);
            });
            req.on('end', function() {
                try {
                    var bodyStr = Buffer.concat(chunks).toString();
                    var result;
                    try {
                        var parsed = JSON.parse(bodyStr);
                        result = postFn(parsed.body || bodyStr);
                    } catch(e) {
                        result = postFn(bodyStr);
                    }
                    res.writeHead(201, { 'Content-Type': 'application/json' });
                    res.end(result);
                } catch(e) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({error: e.message}));
                }
            });
        })
    """)(postFn)
}

/**
 * Handle GET /api/stream — SSE stream for real-time event push.
 */
fun handleSseStream(req: dynamic, res: dynamic) {
    // Set SSE headers
    res.writeHead(200, mapOf(
        "Content-Type" to "text/event-stream",
        "Cache-Control" to "no-cache",
        "Connection" to "keep-alive",
        "Access-Control-Allow-Origin" to "*"
    ))
    
    // Write and flush initial comment
    res.write(": connection established\n\n")
    res.flush()
    
    // Register SSE callback — writes directly to the response
    val writeCallback: (String) -> Unit = { data: String ->
        try {
            res.write(data)
            res.flush()
        } catch (e: Exception) {
            // Client disconnected
        }
    }
    
    // Get initial events and send them
    val initialEvents = Server.registerSseCallback(writeCallback)
    for (event in initialEvents) {
        res.write(event)
    }
    res.flush()
    
    // Handle client disconnect
    req.on("close", {
        Server.unregisterSseCallback(writeCallback)
    })
}
