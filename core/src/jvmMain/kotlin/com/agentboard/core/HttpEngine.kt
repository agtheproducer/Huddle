package com.agentboard.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal actual object HttpEngine {
    private val http = HttpClient.newHttpClient()

    actual fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "GET $url returned ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }

    actual fun post(url: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "POST $url returned ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }
}
