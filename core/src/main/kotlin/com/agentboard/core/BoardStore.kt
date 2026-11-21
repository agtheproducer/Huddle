package com.agentboard.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/** Where coordination events live. The prototype uses one GitHub Issue; tests use memory. */
interface BoardStore {
    fun fetchComments(): List<RawComment>
    fun postComment(body: String): RawComment
}

/** In-memory store for tests, fixtures and offline UI work. */
class InMemoryBoardStore(initial: List<RawComment> = emptyList()) : BoardStore {
    private val comments = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    @Synchronized
    override fun fetchComments(): List<RawComment> = comments.toList()

    @Synchronized
    override fun postComment(body: String): RawComment {
        val c = RawComment(nextId++, Instant.now().toString(), "local", body)
        comments += c
        return c
    }
}

/**
 * Reads and appends comments on one GitHub Issue (the shared event log).
 * The token comes from the environment (GITHUB_TOKEN): never commit it.
 */
class GitHubIssueStore(
    private val repo: String,          // "owner/name"
    private val issueNumber: Int,
    private val token: String,
    private val apiBase: String = "https://api.github.com",
) : BoardStore {
    private val http = HttpClient.newHttpClient()

    @Serializable
    private data class GhUser(val login: String = "")

    @Serializable
    private data class GhComment(
        val id: Long,
        @SerialName("created_at") val createdAt: String,
        val user: GhUser? = null,
        val body: String? = null,
    )

    private fun request(path: String) = HttpRequest.newBuilder(URI.create("$apiBase/repos/$repo/issues/$issueNumber/$path"))
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")

    override fun fetchComments(): List<RawComment> {
        val all = mutableListOf<RawComment>()
        var page = 1
        while (true) {
            val response = http.send(request("comments?per_page=100&page=$page").GET().build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "GitHub returned ${response.statusCode()}: ${response.body().take(200)}" }
            val batch = EventCodec.json.decodeFromString(ListSerializer(GhComment.serializer()), response.body())
            all += batch.map { RawComment(it.id, it.createdAt, it.user?.login ?: "", it.body ?: "") }
            if (batch.size < 100) break
            page++
        }
        return all
    }

    override fun postComment(body: String): RawComment {
        val payload = buildJsonObject { put("body", body) }.toString()
        val response = http.send(
            request("comments").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 201) { "GitHub returned ${response.statusCode()}: ${response.body().take(200)}" }
        val c = EventCodec.json.decodeFromString(GhComment.serializer(), response.body())
        return RawComment(c.id, c.createdAt, c.user?.login ?: "", c.body ?: "")
    }

    companion object {
        /** Builds a store from AGENT_BOARD_REPO, AGENT_BOARD_ISSUE and GITHUB_TOKEN. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): GitHubIssueStore {
            val repo = env["AGENT_BOARD_REPO"] ?: error("Set AGENT_BOARD_REPO=owner/name")
            val issue = env["AGENT_BOARD_ISSUE"]?.toIntOrNull() ?: error("Set AGENT_BOARD_ISSUE to the coordination Issue number")
            val token = env["GITHUB_TOKEN"] ?: error("Set GITHUB_TOKEN (never commit it)")
            return GitHubIssueStore(repo, issue, token)
        }
    }
}
