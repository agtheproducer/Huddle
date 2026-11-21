package com.agentboard.web

import com.agentboard.core.BoardStore
import com.agentboard.core.EventCodec
import com.agentboard.core.RawComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Read-only view of a **public** Issue, with no token.
 *
 * The board view only ever reads, and a public repository's Issue comments are
 * readable without authentication — so a screen showing the board needs no
 * credential at all. That matters for a projector or a spare laptop: nothing to
 * leak, nothing to set up.
 *
 * Unauthenticated REST allows 60 requests an hour per IP. Conditional requests are
 * still used, because a `304 Not Modified` saves transferring and parsing the
 * comments — but measured against the real API, **a 304 does count against the
 * limit** (remaining dropped on every one), contrary to what the docs imply. So
 * the budget, not the payload, is what sets the poll interval: roughly one request
 * a minute without a token.
 *
 * To avoid the screen dying mid-demo, the remaining budget is read from each
 * response and requests stop before it reaches zero, serving the last board until
 * the limit resets. A token lifts this entirely, which is why the live demo should
 * use one.
 *
 * Writing is not supported here on purpose. Claims, messages and handoffs belong
 * to the agents, through `mcp` or `cli`, with their own tokens.
 */
class PublicIssueStore(
    private val repo: String,
    private val issueNumber: Int,
    private val apiBase: String = "https://api.github.com",
) : BoardStore {
    private val http = HttpClient.newHttpClient()
    private var etag: String? = null
    private var cached: List<RawComment> = emptyList()

    /** Last seen rate-limit budget, so we stop before GitHub starts refusing us. */
    private var remaining = Int.MAX_VALUE
    private var resetEpochSeconds = 0L

    /** Kept in hand for paging: never spend the last few requests on a single poll. */
    private val reserve = 3

    private fun noteLimits(response: HttpResponse<*>) {
        response.headers().firstValue("x-ratelimit-remaining").ifPresent { v ->
            v.toIntOrNull()?.let { remaining = it }
        }
        response.headers().firstValue("x-ratelimit-reset").ifPresent { v ->
            v.toLongOrNull()?.let { resetEpochSeconds = it }
        }
    }

    private fun outOfBudget(): Boolean {
        if (remaining > reserve) return false
        val now = System.currentTimeMillis() / 1000
        if (now >= resetEpochSeconds) {
            remaining = Int.MAX_VALUE // the window rolled over; try again
            return false
        }
        return true
    }

    @Serializable
    private data class GhUser(val login: String = "")

    @Serializable
    private data class GhComment(
        val id: Long,
        @SerialName("created_at") val createdAt: String,
        val user: GhUser? = null,
        val body: String? = null,
    )

    private fun request(page: Int): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$apiBase/repos/$repo/issues/$issueNumber/comments?per_page=100&page=$page"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    @Synchronized
    override fun fetchComments(): List<RawComment> {
        // Out of free requests: keep showing the last board rather than erroring.
        if (outOfBudget()) return cached

        val first = request(1).apply { etag?.let { header("If-None-Match", it) } }.GET().build()
        val response = http.send(first, HttpResponse.BodyHandlers.ofString())
        noteLimits(response)

        if (response.statusCode() == 304) return cached
        if (response.statusCode() == 403 || response.statusCode() == 429) {
            // Rate limited. Serve what we have; the next poll will wait for the reset.
            remaining = 0
            return cached
        }
        check(response.statusCode() == 200) {
            "GitHub returned ${response.statusCode()}: ${response.body().take(200)}"
        }

        val all = mutableListOf<RawComment>()
        var batch = parse(response.body())
        all += batch
        var page = 1
        while (batch.size == 100) {
            page++
            val next = http.send(request(page).GET().build(), HttpResponse.BodyHandlers.ofString())
            noteLimits(next)
            check(next.statusCode() == 200) { "GitHub returned ${next.statusCode()}: ${next.body().take(200)}" }
            batch = parse(next.body())
            all += batch
        }

        // Only cache the ETag once every page was read, or a 304 would hide later pages.
        etag = response.headers().firstValue("ETag").orElse(null)
        cached = all
        return all
    }

    private fun parse(body: String): List<RawComment> =
        EventCodec.json.decodeFromString(ListSerializer(GhComment.serializer()), body)
            .map { RawComment(it.id, it.createdAt, it.user?.login ?: "", it.body ?: "") }

    override fun postComment(body: String): RawComment =
        throw UnsupportedOperationException("The board view is read-only. Post through the mcp server or the cli.")
}
