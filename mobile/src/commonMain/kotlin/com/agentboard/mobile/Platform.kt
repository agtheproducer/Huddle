package com.agentboard.mobile

/** The little the app needs from the phone: saved settings and clock formatting for board timestamps. */
expect object Platform {
    fun load(key: String): String?
    fun save(key: String, value: String?)

    /** "just now", "4 min ago", "2 h ago", else the clock time, for an ISO-8601 instant from the board. */
    fun ago(iso: String): String

    /** Local clock time "HH:mm" of an ISO-8601 instant. */
    fun clock(iso: String): String

    /** The local time now, "HH:mm:ss". */
    fun nowClock(): String
}
