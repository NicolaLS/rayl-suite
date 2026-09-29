package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Optional sparkline data, independent of the authoritative aggregated current price. */
class PriceHistory(
    private val fetch: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Point(val time: Long, val price: Double)
    private data class Snapshot(val points: List<Point>, val observedAtMs: Long)

    @Volatile private var latest: Snapshot? = null

    @Volatile private var lastError: String? = null

    suspend fun refresh() {
        try {
            val root =
                Json.parseToJsonElement(fetch()) as? JsonObject ?: error("Expected price history")
            val rows = root["prices"] as? JsonArray ?: error("Expected historical prices")
            val now = clock()
            val cutoff = now / 1000 - 86400
            val points = rows.mapNotNull { row ->
                val item = row as? JsonObject ?: return@mapNotNull null
                val time = (item["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val price = (item["USD"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                if (time !in cutoff..(now / 1000) || !price.isFinite() || price <= 0 ||
                    price >= 1e12
                ) {
                    return@mapNotNull null
                }
                Point(time, price)
            }.distinctBy { it.time }.sortedBy { it.time }.takeLast(24)
            check(points.size >= 2) { "Too little recent price history" }
            latest = Snapshot(points, now)
            lastError = null
        } catch (failure: Exception) {
            if (failure is kotlin.coroutines.cancellation.CancellationException) throw failure
            lastError = failure.message
            throw failure
        }
    }

    /** Omit obsolete history and future points rather than implying a current trend. */
    fun current(asOf: Long): List<Double> {
        val snapshot = latest ?: return emptyList()
        if (clock() - snapshot.observedAtMs > 2 * 3600000L) return emptyList()
        return snapshot.points.filter { it.time >= asOf - 86400 && it.time < asOf }.map { it.price }
            .takeIf { it.size >= 2 } ?: emptyList()
    }

    fun health(): ObservationHealth = ObservationHealth(
        latest?.observedAtMs?.let(::isoTime),
        lastError,
        latest?.let { clock() - it.observedAtMs <= 2 * 3600000L } ?: false
    )

    companion object {
        fun mempool(http: HttpClient): PriceHistory = PriceHistory(fetch = {
            http.prepareGet(
                "https://mempool.space/api/v1/historical-price?currency=USD"
            ).execute { response ->
                check(response.status.isSuccess()) { "Price history HTTP ${response.status.value}" }
                response.bodyAsChannel().readBounded(2 * 1024 * 1024)
                    ?: error("Price history response too large")
            }
        })
    }
}
