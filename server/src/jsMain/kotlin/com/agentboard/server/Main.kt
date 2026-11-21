package com.agentboard.server

/**
 * Entry point for the Node.js HTTP server.
 *
 * Creates an HTTP server that listens on the port specified by the PORT
 * environment variable (default 3000) and routes requests to the handler.
 *
 * For Vercel deployment, the handler is exported directly from Handler.kt.
 */
fun main() {
    // Import Node.js http module
    val http = js("require('http')")
    
    val port = (js("process.env.PORT") as? String)?.toIntOrNull() ?: 3000
    
    // Create server with our handler
    val server = http.createServer({ req: dynamic, res: dynamic ->
        handler(req, res)
    })
    
    server.listen(port, "0.0.0.0")
    js("console.log")("Agent Board server listening on 0.0.0.0:$port")
}
