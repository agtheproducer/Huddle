package com.agentboard.core

/** Compares claimed paths without treating a file-name prefix as a directory. */
internal object PathOverlap {
    private data class Claim(val path: String, val directory: Boolean)

    private fun parse(raw: String): Claim {
        val slashes = raw.replace('\\', '/')
        val directory = slashes.endsWith('/')
        val path = slashes.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return Claim(path, directory)
    }

    fun overlaps(first: String, second: String): Boolean {
        val a = parse(first)
        val b = parse(second)
        if (a.path.isEmpty() || b.path.isEmpty()) return false
        return a.path == b.path ||
            (a.directory && b.path.startsWith("${a.path}/")) ||
            (b.directory && a.path.startsWith("${b.path}/"))
    }
}
