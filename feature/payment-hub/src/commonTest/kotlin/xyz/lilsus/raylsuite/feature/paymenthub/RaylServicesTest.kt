package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalogEntry
import xyz.lilsus.raylsuite.core.raylapi.UsageEvent
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.raylapi.UsageReport
import xyz.lilsus.raylsuite.integration.widget.RaylServiceApi

@OptIn(ExperimentalCoroutinesApi::class)
class RaylServicesTest {
    private val claro = "https://claro.example/rayl/packages.json"
    private val catalog = ServiceCatalog(
        entries = listOf(ServiceCatalogEntry("claro-packages", claro, "mobile")),
        blocked = listOf("evil.example", "https://ok.example/bad.json")
    )

    private class FakeApi(var catalog: ServiceCatalog?) : RaylServiceApi {
        var fetches = 0
        val reports = mutableListOf<UsageReport>()

        override suspend fun fetchCatalog(): ServiceCatalog? {
            fetches += 1
            return catalog
        }

        override suspend fun reportUsage(report: UsageReport) {
            reports += report
        }
    }

    @Test
    fun keepsTheLastCatalogForOfflineUse() = runTest {
        val settings = MapSettings()
        RaylServices(FakeApi(catalog), settings, this).refreshIfOlderThan(0)

        val offline = RaylServices(FakeApi(null), settings, this)
        offline.refreshIfOlderThan(0)
        assertEquals(catalog, offline.catalog.value)
        assertTrue(offline.blocks("https://api.evil.example/w.json"))
    }

    @Test
    fun blocksByHostSubdomainOrExactUrl() = runTest {
        val services = RaylServices(FakeApi(catalog), null, this)
        services.refreshIfOlderThan(0)

        assertTrue(services.blocks("https://evil.example/w.json"))
        assertTrue(services.blocks("https://shop.evil.example/w.json"))
        assertTrue(services.blocks("https://ok.example/bad.json"))
        assertFalse(services.blocks("https://ok.example/good.json"))
        assertFalse(services.blocks(claro))
    }

    @Test
    fun asksAgainOnlyOnceTheCatalogIsOld() = runTest {
        var now = 0L
        val api = FakeApi(catalog)
        val services = RaylServices(api, null, this, clock = { now })

        services.refreshIfOlderThan(RaylServices.REFRESH_AFTER_MS)
        now = RaylServices.REFRESH_AFTER_MS - 1
        services.refreshIfOlderThan(RaylServices.REFRESH_AFTER_MS)
        assertEquals(1, api.fetches)

        now = RaylServices.REFRESH_AFTER_MS
        services.refreshIfOlderThan(RaylServices.REFRESH_AFTER_MS)
        assertEquals(2, api.fetches)
    }

    @Test
    fun reportsOnlyReviewedServicesAndOnlyWhenShared() = runTest {
        var share = true
        val api = FakeApi(catalog)
        val services = RaylServices(api, null, this, shareUsage = { share })
        services.refreshIfOlderThan(0)

        services.report(claro, UsageEventKind.Added)
        services.report("https://unlisted.example/w.json", UsageEventKind.Added)
        advanceUntilIdle()
        share = false
        services.report(claro, UsageEventKind.Paid)
        advanceUntilIdle()

        assertEquals(
            listOf(UsageReport(usagePlatform(), listOf(UsageEvent("claro-packages", UsageEventKind.Added)))),
            api.reports
        )
    }

    @Test
    fun catalogShowsOnlyEntriesWithAValidDefinition() {
        val listed = ServiceCatalog(
            entries = listOf(
                ServiceCatalogEntry("pending", "https://a.example/w.json", "mobile"),
                ServiceCatalogEntry("broken", "https://b.example/w.json", "bills", "{}")
            )
        )
        assertEquals(emptyList(), listed.catalogItems())
    }
}
