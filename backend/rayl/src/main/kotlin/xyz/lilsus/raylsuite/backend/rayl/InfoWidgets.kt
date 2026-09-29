package xyz.lilsus.raylsuite.backend.rayl

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult

/** First-party providers still use the ordinary widget protocol and the client's parser. */
class InfoWidgets(
    private val origin: String,
    private val prices: () -> BitcoinPrices?,
    private val block: () -> BlockReading?,
    private val history: (Long) -> List<Double> = { emptyList() }
) {
    fun definition(id: String): JsonObject? {
        val title = when (id) {
            PRICE -> "Bitcoin price"
            BLOCK_TIME -> "Block time"
            else -> return null
        }
        return buildJsonObject {
            put("spec", WidgetDefinitionParser.SUPPORTED_SPEC)
            put("id", id)
            put("title", title)
            put(
                "description",
                if (id ==
                    PRICE
                ) {
                    "Bitcoin price in US dollars."
                } else {
                    "Latest Bitcoin block height and block timestamp."
                }
            )
            putJsonObject("provider") { put("name", "Rayl") }
            putJsonObject("brand") {
                put("name", "Bitcoin")
                put("icon", ICON)
                put("tint", "#1D1F3A")
            }
            put(
                "sourceUrl",
                if (id ==
                    PRICE
                ) {
                    "$origin/v1/bitcoin/prices"
                } else {
                    "https://mempool.space/"
                }
            )
            putJsonArray("inputs") {}
            put("statusUrl", "$origin/v1/widgets/$id/status")
            putJsonObject("families") {
                putJsonObject("small") { if (id == PRICE) put("label", "BTC") }
                if (id == PRICE) putJsonObject("medium") {}
            }
            putJsonObject("snapshot") {
                putJsonObject("status") {
                    put("title", title)
                    if (id == PRICE) {
                        put("value", "$65,000.00")
                        put("subtitle", "USD per BTC · Rayl")
                        putJsonObject("typedValue") {
                            put("type", "money")
                            put("amount", "65000.00")
                            put("currency", "USD")
                        }
                        putJsonObject("trend") {
                            put("period", "24h")
                            putJsonArray("points") { SAMPLE_TREND.forEach { add(it) } }
                        }
                    } else {
                        put("value", "900,000")
                        put("subtitle", "Latest block · 4m ago")
                        putJsonObject("typedValue") {
                            put("type", "number")
                            put("value", "900000")
                            put("unit", "blocks")
                        }
                    }
                }
            }
        }
    }

    /** Avoid a public-network round trip to our own old deployment during a rolling release. */
    fun definitionSource(fallback: DefinitionSource): DefinitionSource = DefinitionSource { url ->
        val id = listOf(PRICE, BLOCK_TIME).firstOrNull { url == "$origin/v1/widgets/$it.json" }
        if (id == null) {
            fallback.check(url)
        } else {
            val body = definition(id).toString()
            when (val result = WidgetDefinitionParser.parse(url, body)) {
                is WidgetDefinitionResult.Valid -> DefinitionCheck.Valid(body)

                is WidgetDefinitionResult.Invalid -> DefinitionCheck.Rejected(
                    result.problems.toString()
                )
            }
        }
    }

    fun status(id: String): JsonObject? = when (id) {
        PRICE -> priceStatus()
        BLOCK_TIME -> blockStatus()
        else -> null
    }

    private fun priceStatus(): JsonObject? {
        val reading = prices() ?: return null
        val price =
            reading.prices["USD"]?.takeIf { it.isFinite() && it > 0 && it < 1e12 } ?: return null
        val money = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP)
        val points = history(reading.asOf)
        return buildJsonObject {
            put("title", "Bitcoin price")
            put("value", NumberFormat.getCurrencyInstance(Locale.US).format(money))
            put("subtitle", "USD per BTC · Rayl")
            putJsonObject("typedValue") {
                put("type", "money")
                put("amount", money.toPlainString())
                put("currency", "USD")
            }
            put("asOf", Instant.ofEpochSecond(reading.asOf).toString())
            put("refreshAfterSeconds", 30)
            if (points.isNotEmpty()) {
                putJsonObject("trend") {
                    put("period", "24h")
                    putJsonArray("points") {
                        points.forEach { add(it) }
                        add(money.toDouble())
                    }
                }
            }
        }
    }

    private fun blockStatus(): JsonObject? {
        val reading = block() ?: return null
        val seconds = reading.observedAtMs / 1_000 - reading.timestamp
        val age = when {
            seconds < 0 -> "timestamp ahead of clock"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
        return buildJsonObject {
            put("title", "Block time")
            put("value", NumberFormat.getIntegerInstance(Locale.US).format(reading.height))
            put("subtitle", "Latest block · $age")
            putJsonObject("typedValue") {
                put("type", "number")
                put("value", reading.height.toString())
                put("unit", "blocks")
            }
            // The displayed age describes this observation, not the header's historical time.
            put("asOf", isoTime(reading.observedAtMs))
            put("refreshAfterSeconds", 30)
        }
    }

    companion object {
        const val PRICE = "bitcoin-price"
        const val BLOCK_TIME = "block-time"
        private val SAMPLE_TREND =
            listOf(64100, 64420, 64250, 64800, 64650, 65100, 64900, 65300, 65050, 65000)
        private val ICON = "data:image/png;base64," + Base64.getEncoder().encodeToString(
            checkNotNull(InfoWidgets::class.java.getResourceAsStream("/widgets/bitcoin.png")).use {
                it.readBytes()
            }
        )
    }
}
