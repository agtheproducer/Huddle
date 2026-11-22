package com.agentboard.server

import com.agentboard.core.RawComment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The board's memory on disk: every event as one JSON array in [path].
 *
 * Writes go to a temp file that is then renamed over the real one, so a crash
 * mid-write leaves the previous version intact, never half a file. A file that
 * cannot be read back is moved aside (`<path>.corrupt-<timestamp>`) instead of
 * crashing the server or being silently overwritten.
 *
 * Wiping the board for a clean demo is deleting this one file.
 */
class EventFile(path: String) {
    private val fs = js("require('fs')")
    private val nodePath = js("require('path')")

    /** Absolute, so logs show exactly which file is the board. */
    val path: String = nodePath.resolve(path) as String
    private val serializer = ListSerializer(RawComment.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** The stored events, or none if the file is missing, empty or unreadable. */
    fun load(): List<RawComment> {
        if (!(fs.existsSync(path) as Boolean)) return emptyList()
        val text = fs.readFileSync(path, "utf8") as String
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            val aside = "$path.corrupt-${js("Date.now()")}"
            fs.renameSync(path, aside)
            console.error("Board data file $path is not valid JSON (${e.message}); moved it to $aside and started empty.")
            emptyList()
        }
    }

    /** Replaces the file with [comments], atomically. */
    fun save(comments: List<RawComment>) {
        fs.mkdirSync(nodePath.dirname(path), js("({ recursive: true })"))
        val tmp = "$path.tmp"
        fs.writeFileSync(tmp, json.encodeToString(serializer, comments))
        fs.renameSync(tmp, path)
    }
}
