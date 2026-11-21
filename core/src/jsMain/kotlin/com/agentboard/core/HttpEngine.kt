package com.agentboard.core

internal actual object HttpEngine {
    actual fun get(url: String): String {
        throw NotImplementedError("HttpEngine.get is not needed on JS target — the server module handles HTTP directly")
    }

    actual fun post(url: String, body: String): String {
        throw NotImplementedError("HttpEngine.post is not needed on JS target — the server module handles HTTP directly")
    }
}
