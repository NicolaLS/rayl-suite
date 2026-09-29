package xyz.lilsus.raylsuite.integration.exchangerate

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/** Reads bitcoin prices from the Rayl backend, which aggregates several public sources. */
class RaylBitcoinPriceProvider(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = RaylApi.BASE_URL,
    private val clock: () -> Long = ::platformCurrentTimeMillis
) : BitcoinPriceProvider {
    fun close() = client.close()

    private val cacheMutex = Mutex()
    private var cached: CachedPrices? = null

    override suspend fun pricePerBitcoin(fiatCurrencyCode: String): Double? {
        val currencyCode =
            fiatCurrencyCode
                .trim()
                .uppercase()
                .takeIf(String::isNotEmpty)
                ?: return null
        val prices = cacheMutex.withLock {
            cached
                ?.takeIf { clock() - it.storedAtMs < CACHE_TTL_MS }
                ?.prices
                ?: fetchPrices()?.also { cached = CachedPrices(it, storedAtMs = clock()) }
        }
        return prices?.get(currencyCode)?.takeIf { it.isFinite() && it > 0.0 }
    }

    private suspend fun fetchPrices(): Map<String, Double>? = try {
        val response = client.get(baseUrl + RaylApi.BITCOIN_PRICES_PATH)
        if (response.status.isSuccess()) {
            json.decodeFromString(BitcoinPrices.serializer(), response.bodyAsText()).prices
        } else {
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private class CachedPrices(val prices: Map<String, Double>, val storedAtMs: Long)

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal expect fun platformCurrentTimeMillis(): Long
