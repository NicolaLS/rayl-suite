package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Debug-build timing marks for the payment path, in milliseconds since timings were enabled at
 * process start. Logged under the `RaylTiming` tag (logcat on Android, the unified log on iOS).
 */
object PocketTimings {
    private val start = TimeSource.Monotonic.markNow()

    @Volatile
    var enabled = false

    fun mark(event: String) {
        if (enabled) {
            platformLog(
                "RaylTiming",
                "${start.elapsedNow().inWholeMilliseconds} ms $event"
            )
        }
    }
}

internal expect fun platformLog(tag: String, message: String)
