package com.agentboard.core

internal actual inline fun <T> Any.withLockCompat(block: () -> T): T = synchronized(this) { block() }
