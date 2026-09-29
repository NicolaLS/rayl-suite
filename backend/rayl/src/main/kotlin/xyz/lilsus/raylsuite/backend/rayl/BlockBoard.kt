package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class BlockReading(val height: Long, val timestamp: Long, val observedAtMs: Long)

/** One backend poll serves every device; failed polls keep the last observation with its age. */
class BlockBoard(
    private val fetch: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 5 * 60_000L
) {
    @Volatile private var latest: BlockReading? = null

    @Volatile private var lastError: String? = null

    suspend fun refresh() {
        try {
            val root =
                Json.parseToJsonElement(fetch()) as? JsonArray ?: error("Expected Bitcoin blocks")
            val now = clock()
            val readings = root.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val height =
                    (item["height"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val timestamp =
                    (item["timestamp"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                if (height < 0 || timestamp <= 0 ||
                    timestamp > now / 1_000 + 7_200
                ) {
                    return@mapNotNull null
                }
                BlockReading(height, timestamp, now)
            }
            latest = readings.maxByOrNull { it.height } ?: error("No usable Bitcoin blocks")
            lastError = null
        } catch (failure: Exception) {
            if (failure is kotlin.coroutines.cancellation.CancellationException) throw failure
            lastError = failure.message
            throw failure
        }
    }

    fun current(): BlockReading? = latest?.takeIf { clock() - it.observedAtMs <= maxAgeMs }

    fun health(): ObservationHealth = ObservationHealth(
        latest?.observedAtMs?.let(::isoTime),
        lastError,
        current() != null
    )

    companion object {
        fun mempool(http: HttpClient): BlockBoard = BlockBoard(fetch = {
            http.prepareGet("https://mempool.space/api/blocks").execute { response ->
                check(response.status.isSuccess()) {
                    "Bitcoin blocks HTTP ${response.status.value}"
                }
                response.bodyAsChannel().readBounded(64 * 1024)
                    ?: error("Bitcoin blocks response too large")
            }
        })
    }
}
