package com.agentboard.stage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.URLDecoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Rehearsal stage: one local page for the demo.
 *
 *   /            the stage page (files of demo/calculator, code viewer, the running app)
 *   /app/        the calculator frontend, served from disk, so agents' edits show at once
 *   /api/files   the files under demo/calculator (no build output) with size and last change
 *   /api/file    one file's text, ?path=frontend/app.js (only inside demo/calculator)
 *   /backend/... the calculator backend (CALC_URL, default http://localhost:8090), passed through,
 *                so the page and the app reach it from the same origin in any browser
 *
 * Listens on localhost only. Port: STAGE_PORT, default 8095. Root: BOB_ROOT, default the working directory.
 */
fun main() {
    val root = Path.of(System.getenv("BOB_ROOT") ?: System.getProperty("user.dir")).toAbsolutePath().normalize()
    val calc = root.resolve("demo/calculator").normalize()
    require(Files.isDirectory(calc)) { "No demo/calculator under $root. Set BOB_ROOT to the repo." }
    val front = calc.resolve("frontend")
    val port = System.getenv("STAGE_PORT")?.toIntOrNull() ?: 8095

    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
    server.createContext("/") { ex ->
        try {
            val path = ex.requestURI.path
            when {
                path == "/" || path == "/index.html" -> page(ex)
                path == "/api/files" -> send(ex, 200, files(calc), "application/json")
                path == "/api/file" -> fileText(ex, calc)
                path == "/app" -> redirect(ex, "/app/")
                path.startsWith("/app/") -> static(ex, front, path.removePrefix("/app/").ifEmpty { "index.html" })
                path.startsWith("/backend/") -> proxy(ex, path.removePrefix("/backend"))
                else -> send(ex, 404, "not found", "text/plain")
            }
        } catch (e: Exception) {
            send(ex, 500, e.message ?: "error", "text/plain")
        }
    }
    server.start()
    println("Rehearsal stage on http://localhost:$port  (showing $calc)")
}

private val SKIP = setOf("build", ".gradle", ".kotlin", "node_modules", ".idea")

private fun files(calc: Path): String {
    val entries = Files.walk(calc).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { f -> calc.relativize(f).none { it.name in SKIP } && !f.name.startsWith(".") }
            .sorted()
            .map { f ->
                val rel = calc.relativize(f).joinToString("/")
                """{"path":${jsonString(rel)},"size":${Files.size(f)},"modified":${Files.getLastModifiedTime(f).toMillis()}}"""
            }
            .toList()
    }
    return """{"root":"demo/calculator","now":${System.currentTimeMillis()},"files":[${entries.joinToString(",")}]}"""
}

private fun fileText(ex: HttpExchange, calc: Path) {
    val f = inside(calc, query(ex)["path"])
    if (f == null || !Files.isRegularFile(f)) return send(ex, 404, "not found", "text/plain")
    val text = if (Files.size(f) > 512 * 1024) "(file too large to show)" else try {
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(f))).toString()
    } catch (_: CharacterCodingException) {
        "(binary file)"
    }
    send(ex, 200, text, "text/plain")
}

private fun static(ex: HttpExchange, dir: Path, rel: String) {
    val f = inside(dir, URLDecoder.decode(rel, StandardCharsets.UTF_8))
    if (f == null || !Files.isRegularFile(f)) return send(ex, 404, "not found", "text/plain")
    var bytes = Files.readAllBytes(f)
    // The app's default backend is http://localhost:8090. Served here, point it at /backend instead
    // (same origin, so no browser blocks it). Only the copy sent to the browser changes, never the file.
    if (f.name == "index.html") {
        bytes = String(bytes, StandardCharsets.UTF_8).replace("value=\"$CALC\"", "value=\"/backend\"").toByteArray(StandardCharsets.UTF_8)
    }
    send(ex, 200, bytes, mime(f))
}

private val CALC = (System.getenv("CALC_URL") ?: "http://localhost:8090").trimEnd('/')
private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

/** GET only: the calculator API is read-only. */
private fun proxy(ex: HttpExchange, rest: String) {
    if (ex.requestMethod != "GET") return send(ex, 405, "GET only", "text/plain")
    val target = CALC + rest + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
    val res = try {
        http.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    } catch (e: Exception) {
        return send(ex, 502, """{"error":"calculator backend not running at $CALC"}""", "application/json")
    }
    send(ex, res.statusCode(), res.body(), res.headers().firstValue("Content-Type").orElse("application/json").substringBefore(';'))
}

private fun page(ex: HttpExchange) {
    val html = object {}.javaClass.getResourceAsStream("/stage.html")!!.use { it.readBytes() }
    send(ex, 200, html, "text/html")
}

/** [rel] resolved under [base], or null if it would leave it. */
private fun inside(base: Path, rel: String?): Path? {
    if (rel.isNullOrBlank()) return null
    val f = base.resolve(rel).normalize()
    return f.takeIf { it.startsWith(base) && base.relativize(it).none { p -> p.name in SKIP } }
}

private fun mime(f: Path) = when (f.extension.lowercase()) {
    "html" -> "text/html"
    "css" -> "text/css"
    "js" -> "text/javascript"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    else -> "text/plain"
}

private fun query(ex: HttpExchange): Map<String, String> =
    ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
    }

private fun jsonString(s: String): String = buildString {
    append('"')
    s.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

private fun redirect(ex: HttpExchange, to: String) {
    ex.responseHeaders.add("Location", to)
    ex.sendResponseHeaders(302, -1)
    ex.close()
}

private fun send(ex: HttpExchange, status: Int, body: String, type: String) =
    send(ex, status, body.toByteArray(StandardCharsets.UTF_8), type)

private fun send(ex: HttpExchange, status: Int, body: ByteArray, type: String) {
    ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
    // Always fresh: the point is to see the agents' edits as they land.
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
}
