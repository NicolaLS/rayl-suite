package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetFamily
import xyz.lilsus.raylsuite.core.widgetspec.WidgetTypedValue
import xyz.lilsus.raylsuite.core.widgetspec.parseWidgetStatusResponse

class InfoWidgetsTest {
    private val origin = "https://rayl-staging.up.railway.app"

    @Test
    fun definitionsUseTheOrdinaryStatusProtocol() {
        val widgets = InfoWidgets(origin, { null }, { null })
        for (id in listOf(InfoWidgets.PRICE, InfoWidgets.BLOCK_TIME)) {
            val definition = assertIs<WidgetDefinitionResult.Valid>(
                WidgetDefinitionParser.parse(
                    "$origin/v1/widgets/$id.json",
                    widgets.definition(id).toString()
                )
            ).definition
            assertEquals(id, definition.id)
            assertNull(definition.purchase)
            assertEquals(emptyList(), definition.inputs)
        }
        assertNull(widgets.definition("unknown"))
        assertNull(widgets.status(InfoWidgets.PRICE))
        assertNull(widgets.status(InfoWidgets.BLOCK_TIME))
    }

    @Test
    fun builtInCatalogDefinitionsDoNotDependOnOurPublicDeployment() = runTest {
        for (environment in listOf("staging", "production")) {
            val base = "https://rayl-$environment.up.railway.app"
            val widgets = InfoWidgets(base, { null }, { null })
            val source = widgets.definitionSource { error("Unexpected network request") }
            val catalog = CatalogService(
                CatalogSource(
                    CatalogSource.load(environment).entries.filter { it.discoverable }
                ),
                source
            )
            catalog.refresh()
            assertEquals(2, catalog.current().entries.size)
            for (entry in catalog.current().entries) {
                val definition = assertIs<WidgetDefinitionResult.Valid>(
                    WidgetDefinitionParser.parse(entry.url, assertNotNull(entry.definition))
                ).definition
                assertEquals("#1D1F3A", definition.brand?.tint)
                assertTrue(!definition.brand?.iconPng.isNullOrEmpty())
                assertEquals(
                    if (entry.id ==
                        InfoWidgets.PRICE
                    ) {
                        listOf(WidgetFamily.Small, WidgetFamily.Medium)
                    } else {
                        listOf(WidgetFamily.Small)
                    },
                    definition.supportedFamilies
                )
            }
        }
    }

    @Test
    fun liveTrendEndsAtTheCurrentAggregatedRoundedQuote() {
        val widgets =
            InfoWidgets(origin, {
                BitcoinPrices(1790000000, mapOf("USD" to 83428.125))
            }, { null }, { listOf(81000.0, 82000.0) })
        val status =
            assertNotNull(
                parseWidgetStatusResponse(200, widgets.status(InfoWidgets.PRICE).toString())
            )
        assertEquals(listOf(81000.0, 82000.0, 83428.13), status.trend?.points)
    }

    @Test
    fun priceUsesTheCachedQuoteAndItsObservationTime() {
        val widgets =
            InfoWidgets(origin, {
                BitcoinPrices(1_790_000_000, mapOf("USD" to 83428.125))
            }, { null })
        val status =
            assertNotNull(
                parseWidgetStatusResponse(200, widgets.status(InfoWidgets.PRICE).toString())
            )
        assertEquals("83428.13", assertIs<WidgetTypedValue.Money>(status.typedValue).amount)
        assertEquals("$83,428.13", status.value)
        assertEquals("2026-09-21T14:13:20Z", status.asOf)
        assertEquals(status.asOf, status.observedAt)
    }

    @Test
    fun blockAgeDistinguishesTheHeaderTimestampFromObservation() {
        val now = 1_790_000_000_000L
        val widgets = InfoWidgets(origin, { null }, {
            BlockReading(
                900000,
                now / 1000 - 631,
                now - 30000
            )
        })
        val status =
            assertNotNull(
                parseWidgetStatusResponse(200, widgets.status(InfoWidgets.BLOCK_TIME).toString())
            )
        assertEquals("900,000", status.value)
        assertEquals("Latest block · 10m ago", status.subtitle)
        assertEquals("900000", assertIs<WidgetTypedValue.Number>(status.typedValue).value)
        assertEquals("2026-09-21T14:12:50Z", status.observedAt)
        assertEquals(status.observedAt, status.asOf)
        val future =
            InfoWidgets(origin, { null }, { BlockReading(1, now / 1000 + 60, now) })
        assertEquals(
            "Latest block · timestamp ahead of clock",
            parseWidgetStatusResponse(
                200,
                future.status(InfoWidgets.BLOCK_TIME).toString()
            )?.subtitle
        )
    }

    @Test
    fun failedPollsRetainTheirOriginalAgeAndExpire() = runTest {
        var now = 1_790_000_000_000L
        var body = """[{"height":2,"timestamp":1789999900},{"height":3,"timestamp":1789999950}]"""
        val board = BlockBoard({ body }, { now })
        assertNull(board.current())
        board.refresh()
        assertEquals(BlockReading(3, 1789999950, now), board.current())
        now += 60000
        body = "[]"
        assertFails { board.refresh() }
        assertEquals(now - 60000, board.current()?.observedAtMs)
        assertNotNull(board.health().lastError)
        assertTrue(board.health().fresh)
        now += 240001
        assertNull(board.current())
        kotlin.test.assertFalse(board.health().fresh)
        // A reorganisation may legitimately lower the height; accept a new observation.
        body = """[{"height":2,"timestamp":1789999900}]"""
        board.refresh()
        assertEquals(2, board.current()?.height)
        assertNull(board.health().lastError)
    }
}
