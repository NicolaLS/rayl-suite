package xyz.lilsus.raylsuite.backend.rayl

import java.time.Instant
import kotlinx.serialization.Serializable

/** What `GET /v1/status` shows: enough to tell "down" from "waiting" at a glance. */
@Serializable
data class BackendHealth(
    val environment: String,
    val startedAt: String,
    val prices: List<SourceHealth>,
    val catalog: List<EntryHealth>,
    val blocked: List<String>,
    val usage: UsageHealth,
    val blocks: ObservationHealth? = null,
    val priceHistory: ObservationHealth? = null
)

@Serializable
data class ObservationHealth(val lastSuccess: String?, val lastError: String?, val fresh: Boolean)

@Serializable
data class SourceHealth(
    val name: String,
    val lastSuccess: String?,
    val currencies: Int,
    val lastError: String?
)

@Serializable
data class EntryHealth(
    val id: String,
    val url: String,
    /** Whether clients currently get a definition for this entry. */
    val served: Boolean,
    val lastCheck: String?,
    val lastResult: String?
)

@Serializable
data class UsageHealth(
    val pendingCounters: Int,
    val lastFlush: String?,
    val lastFlushError: String?
)

internal fun isoTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
