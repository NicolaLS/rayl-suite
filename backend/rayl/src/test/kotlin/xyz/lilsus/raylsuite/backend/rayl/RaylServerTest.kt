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
    fun catalogHidesLinkOnlyEntriesUnlessExplicitlyRequested() = testApplication {
        val visible = ServiceCatalogEntry("price", "https://info.example/a.json", "info", "{}")
        val hidden =
            ServiceCatalogEntry(
                "claro",
                "https://claro.example/a.json",
                "mobile",
                "{}",
                discoverable = false
            )
        val catalog = ServiceCatalog(listOf(visible, hidden), listOf("blocked.example"))
        application { raylModule(bitcoinPrices = { null }, catalog = { catalog }) }
        val public = Json.decodeFromString<ServiceCatalog>(
            client.get(RaylApi.CATALOG_PATH).bodyAsText()
        )
        assertEquals(listOf(visible), public.entries)
        assertEquals(catalog.blocked, public.blocked)
        val full = client.get("${RaylApi.CATALOG_PATH}?${RaylApi.INCLUDE_LINK_ONLY}=true")
        assertEquals(catalog, Json.decodeFromString<ServiceCatalog>(full.bodyAsText()))
    }

    @Test
    fun widgetStatusRoutesValidateRequestsAndReportMissingData() = testApplication {
        application {
            raylModule(bitcoinPrices = {
                null
            }, infoWidgets = InfoWidgets("https://info.example", { null }, { null }))
        }
        assertEquals(HttpStatusCode.OK, client.get("/v1/widgets/bitcoin-price.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/widgets/unknown.json").status)
        val path = "/v1/widgets/bitcoin-price/status"
        for (body in listOf(
            "nope",
            "{}",
            "[]",
            """{"spec":"rayl-widget/1","widgetId":"bitcoin-price","inputs":[]}""",
            """{"spec":"rayl-widget/1","widgetId":"bitcoin-price","inputs":{"a":1}}""",
            "x".repeat(9000),
            """{"spec":"rayl-widget/1","widgetId":"block-time"}"""
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.post(path) { setBody(body) }.status)
        }
        val response = client.post(path) {
            setBody("""{"spec":"rayl-widget/1","widgetId":"bitcoin-price","inputs":{}}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun servesLiveWidgetDataThroughTheOrdinaryProtocol() = testApplication {
        val widgets =
            InfoWidgets("https://info.example", {
                BitcoinPrices(1790000000, mapOf("USD" to 65000.0))
            }, { BlockReading(900000, 1789999700, 1790000000000) })
        application { raylModule(bitcoinPrices = { null }, infoWidgets = widgets) }
        for (id in listOf(InfoWidgets.PRICE, InfoWidgets.BLOCK_TIME)) {
            val response = client.post("/v1/widgets/$id/status") {
                setBody("""{"spec":"rayl-widget/1","widgetId":"$id","inputs":{}}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            kotlin.test.assertNotNull(
                xyz.lilsus.raylsuite.core.widgetspec.parseWidgetStatusResponse(
                    response.status.value,
                    response.bodyAsText()
                )
            )
        }
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
