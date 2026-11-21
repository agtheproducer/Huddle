package com.agentboard.core

/**
 * Platform-agnostic HTTP helper for [HttpBoardStore].
 *
 * Each target (JS, JVM) provides its own implementation via [actual].
 */
internal expect object HttpEngine {
    /** Perform a GET request and return the response body as a String. */
    fun get(url: String): String

    /**
     * Perform a POST request with a JSON body and return the response body as a String.
     * @param url The URL to POST to.
     * @param body The JSON body string.
     */
    fun post(url: String, body: String): String
}
