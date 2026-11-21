package com.agentboard.calc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Calculator backend for the Agent Board demo.
 *
 *   GET /api/calc?a=12&op=add&b=3  ->  200 {"result":15.0}
 *   bad input or division by zero  ->  400 {"error":"..."}
 *
 * CORS is open so the HTML frontend can call it from a file or any port.
 * Port: CALC_PORT, default 8090.
 */
fun main() {
    val port = System.getenv("CALC_PORT")?.toIntOrNull() ?: 8090
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/api/calc") { ex ->
        if (ex.requestMethod == "OPTIONS") return@createContext respond(ex, 204, "")
        val q = query(ex)
        try {
            val result = Calculator.calculate(
                Calculator.number("a", q["a"]),
                Op.parse(q["op"]),
                Calculator.number("b", q["b"]),
            )
            respond(ex, 200, """{"result":$result}""")
        } catch (e: CalcException) {
            respond(ex, 400, """{"error":"${e.message}"}""")
        }
    }
    server.createContext("/") { ex -> respond(ex, 200, """{"service":"calculator","try":"/api/calc?a=12&op=add&b=3"}""") }

    server.start()
    println("Calculator backend on http://localhost:$port  (try /api/calc?a=12&op=add&b=3)")
}

private fun query(ex: HttpExchange): Map<String, String> =
    ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
    }

private fun respond(ex: HttpExchange, status: Int, json: String) {
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.apply {
        add("Content-Type", "application/json; charset=utf-8")
        add("Access-Control-Allow-Origin", "*")
        add("Access-Control-Allow-Methods", "GET, OPTIONS")
    }
    ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
}
