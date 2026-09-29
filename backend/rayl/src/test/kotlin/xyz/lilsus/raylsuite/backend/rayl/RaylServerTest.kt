package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalogEntry
import xyz.lilsus.raylsuite.core.raylapi.UsageEvent
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.raylapi.UsageReport

class RaylServerTest {
    @Test
    fun servesCachedPrices() = testApplication {
        val prices = BitcoinPrices(asOf = 1_790_705_710, prices = mapOf("USD" to 83_428.0))
        application { raylModule(bitcoinPrices = { prices }) }

        val response = client.get(RaylApi.BITCOIN_PRICES_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=15", response.headers[HttpHeaders.CacheControl])
        assertEquals(prices, Json.decodeFromString<BitcoinPrices>(response.bodyAsText()))
    }

    @Test
    fun reportsWhenNoSourceIsFresh() = testApplication {
        application { raylModule(bitcoinPrices = { null }) }

        val response = client.get(RaylApi.BITCOIN_PRICES_PATH)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun servesTheCatalog() = testApplication {
        val catalog = ServiceCatalog(
            entries = listOf(
                ServiceCatalogEntry("claro", "https://claro.example/a.json", "mobile", "{}")
            ),
            blocked = listOf("evil.example")
        )
        application { raylModule(bitcoinPrices = { null }, catalog = { catalog }) }

        val response = client.get(RaylApi.CATALOG_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=60", response.headers[HttpHeaders.CacheControl])
        assertEquals(catalog, Json.decodeFromString<ServiceCatalog>(response.bodyAsText()))
    }

    @Test
    fun acceptsUsageReportsAndRejectsGarbage() = testApplication {
        val reports = mutableListOf<UsageReport>()
        application { raylModule(bitcoinPrices = { null }, recordUsage = { reports += it }) }

        val accepted = client.post(RaylApi.USAGE_PATH) {
            setBody("""{"platform":"ios","events":[{"entry":"claro","event":"paid"}],"extra":1}""")
        }
        assertEquals(HttpStatusCode.NoContent, accepted.status)
        assertEquals(
            listOf(UsageReport("ios", listOf(UsageEvent("claro", UsageEventKind.Paid)))),
            reports
        )

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post(RaylApi.USAGE_PATH) {
                setBody("nope")
            }.status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post(RaylApi.USAGE_PATH) { setBody("x".repeat(9_000)) }.status
        )
        assertEquals(1, reports.size)
    }
}
