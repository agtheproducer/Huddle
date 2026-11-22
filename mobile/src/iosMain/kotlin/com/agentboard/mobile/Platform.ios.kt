package com.agentboard.mobile

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSinceDate

actual object Platform {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(key: String): String? = defaults.stringForKey(key)

    actual fun save(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }

    private val withFraction = NSISO8601DateFormatter().apply {
        formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
    }
    private val plain = NSISO8601DateFormatter()
    private val hm = NSDateFormatter().apply { dateFormat = "HH:mm" }
    private val hms = NSDateFormatter().apply { dateFormat = "HH:mm:ss" }

    private fun parse(iso: String): NSDate? = withFraction.dateFromString(iso) ?: plain.dateFromString(iso)

    actual fun ago(iso: String): String {
        val date = parse(iso) ?: return ""
        val s = NSDate().timeIntervalSinceDate(date).toLong()
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 6 * 3600 -> "${s / 3600} h ago"
            else -> hm.stringFromDate(date)
        }
    }

    actual fun clock(iso: String): String = parse(iso)?.let { hm.stringFromDate(it) }.orEmpty()

    actual fun nowClock(): String = hms.stringFromDate(NSDate())
}
