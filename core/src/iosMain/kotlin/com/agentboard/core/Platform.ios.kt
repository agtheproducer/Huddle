package com.agentboard.core

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

internal actual fun nowIso(): String = NSISO8601DateFormatter().stringFromDate(NSDate())

/** The mobile app reads the board from one coroutine at a time, so no lock is needed. */
internal actual inline fun <T> Any.withLockCompat(block: () -> T): T = block()
