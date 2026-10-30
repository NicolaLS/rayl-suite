package xyz.lilsus.rayl.providers.rayl

import kotlin.time.Instant

/** Unix seconds of an ISO-8601 instant from the Club backend, or null if absent or malformed. */
internal fun clubEpochSeconds(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }
