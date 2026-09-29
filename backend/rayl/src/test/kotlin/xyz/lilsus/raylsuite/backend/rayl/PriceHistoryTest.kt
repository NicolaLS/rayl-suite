package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PriceHistoryTest {
    @Test
    fun historyIsOrderedBoundedAndRestrictedToTheQuotesTimeWindow() = runTest {
        val now = 1790000000000L
        val body = """{"prices":[
            {"time":1789999990,"USD":81000},{"time":1789999950,"USD":80000},
            {"time":1789999990,"USD":82000},{"time":1790000010,"USD":83000},
            {"time":1789800000,"USD":10000},{"time":1789999940,"USD":-1}] }"""
        val history = PriceHistory({ body }, { now })
        history.refresh()
        assertEquals(listOf(80000.0, 81000.0), history.current(now / 1000))
        assertEquals(emptyList(), history.current(1789999990))
        assertTrue(history.health().fresh)
    }

    @Test
    fun failedRefreshDoesNotMakeOldHistoryFreshAndEventuallyOmitsTheChart() = runTest {
        var now = 1790000000000L
        var body = """{"prices":[
            {"time":1789999900,"USD":80000},{"time":1789999950,"USD":81000}]}"""
        val history = PriceHistory({ body }, { now })
        history.refresh()
        val first = history.health().lastSuccess
        now += 1800000
        body = "{}"
        assertFails { history.refresh() }
        assertEquals(first, history.health().lastSuccess)
        assertNotNull(history.health().lastError)
        assertEquals(2, history.current(now / 1000).size)
        now += 5400001
        assertEquals(emptyList(), history.current(now / 1000))
        assertFalse(history.health().fresh)
    }

    @Test
    fun upstreamResponsesHaveAHardSizeLimit() = runTest {
        HttpClient(MockEngine { respond("x".repeat(2 * 1024 * 1024 + 1)) }).use { http ->
            val history = PriceHistory.mempool(http)
            assertFails { history.refresh() }
            assertFalse(history.health().fresh)
            assertEquals("Price history response too large", history.health().lastError)
        }
    }
}
