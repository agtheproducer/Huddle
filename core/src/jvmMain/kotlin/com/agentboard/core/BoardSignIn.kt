package com.agentboard.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

/** Requests carrying credentials never follow redirects. */
object BoardSignIn {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()

    fun endpoint(raw: String): String {
        val uri = URI(raw.trim().trimEnd('/'))
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.isNullOrEmpty() && (uri.scheme == "https" ||
            uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
            "Enter an HTTPS board origin (or HTTP localhost)."
        }
        return uri.toString()
    }

    fun request(url: String, body: String? = null, credential: String? = null): String {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        if (credential != null) builder.header("Authorization", "Bearer $credential")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            if (response.statusCode() == 401) "Credential expired or declined. Sign in again."
            else "Sign-in or board request failed (HTTP ${response.statusCode()})."
        }
        return response.body()
    }

    fun exchange(board: String, githubToken: String): JsonObject =
        Json.parseToJsonElement(request(endpoint(board) + "/api/auth/session", "", githubToken)).jsonObject

    fun postEvent(board: String, body: String, session: String): String =
        request(endpoint(board) + "/api/events", body, session)

    fun github(path: String, fields: Map<String, String>): JsonObject {
        val body = JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        return Json.parseToJsonElement(request("https://github.com/login/$path", body.toString())).jsonObject
    }
}
