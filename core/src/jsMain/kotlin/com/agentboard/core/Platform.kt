package com.agentboard.core

import kotlin.js.Date

internal actual fun nowIso(): String = Date().toISOString()
