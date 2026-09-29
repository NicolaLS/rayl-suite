package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class PriceSourcesTest {
    @Test
    fun mempoolDropsTheTimestamp() {
        val body = """{"time":1790705710,"USD":83428,"EUR":73567}"""
        assertEquals(
            mapOf("USD" to 83_428.0, "EUR" to 73_567.0),
            MempoolSource.parse(Json.parseToJsonElement(body))
        )
    }

    @Test
    fun coinbaseKeepsOnlyFiatAndReadsStringRates() {
        val rates =
            """{"data":{"rates":{"USD":"83417.55","ETH":"30.1","BOB":"576000.1"}}}"""
        val currencies = """{"data":[{"id":"USD","name":"US Dollar"},{"id":"EUR","name":"Euro"}]}"""
        assertEquals(
            mapOf("USD" to 83_417.55),
            CoinbaseSource.parse(
                Json.parseToJsonElement(rates),
                Json.parseToJsonElement(currencies)
            )
        )
    }

    @Test
    fun krakenReadsTheLastTradeUnderEitherPairSpelling() {
        val body =
            """
            {"error":[],"result":{
              "XXBTZUSD":{"a":["83436.0","1","1.000"],"c":["83435.9","0.00005"]},
              "XBTCHF":{"c":["69610.0","0.1"]}
            }}
            """.trimIndent()
        assertEquals(
            mapOf("USD" to 83_435.9, "CHF" to 69_610.0),
            KrakenSource.parse(Json.parseToJsonElement(body))
        )
    }

    @Test
    fun krakenErrorsFail() {
        val body = """{"error":["EGeneral:Too many requests"],"result":{}}"""
        assertFailsWith<IllegalStateException> { KrakenSource.parse(Json.parseToJsonElement(body)) }
    }

    @Test
    fun blockchainInfoReadsLast() {
        val body =
            """{"ARS":{"15m":1.27E8,"last":1.27149654E8,"symbol":"ARS"},"USD":{"last":83407.08}}"""
        assertEquals(
            mapOf("ARS" to 127_149_654.0, "USD" to 83_407.08),
            BlockchainInfoSource.parse(Json.parseToJsonElement(body))
        )
    }
}
