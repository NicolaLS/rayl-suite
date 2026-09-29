package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog

class CatalogTest {
    @Test
    fun bundledCatalogsAreValid() {
        for (environment in listOf("staging", "production")) {
            val source = CatalogSource.load(environment)
            assertEquals(emptyList(), source.problems(), environment)
            assertEquals(
                listOf("bitcoin-price", "block-time"),
                source.entries.filter {
                    it.discoverable
                }.map { it.id },
                environment
            )
        }
    }

    @Test
    fun reportsEveryProblem() {
        val source = CatalogSource(
            entries = listOf(
                CatalogSourceEntry("claro", "mobile", "https://claro.example/a.json"),
                CatalogSourceEntry("claro", "phones", "http://claro.example/b.json"),
                CatalogSourceEntry("Bad Id", "info", "https://claro.example/a.json")
            ),
            blocked = listOf("evil.example", "not a host", "https://evil.example/x.json")
        )
        assertEquals(
            listOf(
                "duplicate id claro",
                "duplicate url https://claro.example/a.json",
                "unknown section phones for claro",
                "url must be normalized HTTPS: http://claro.example/b.json",
                "invalid id Bad Id",
                "invalid blocked rule not a host"
            ),
            source.problems()
        )
    }

    @Test
    fun emergencyRulesAddToTheCatalog() {
        val source = CatalogSource(emptyList(), blocked = listOf("a.example"))
            .withBlocked(" b.example, a.example ,,https://c.example/w.json")
        assertEquals(listOf("a.example", "b.example", "https://c.example/w.json"), source.blocked)
    }

    @Test
    fun blockRulesCoverSubdomainsAndExactUrls() {
        val catalog =
            ServiceCatalog(blocked = listOf("evil.example", "https://ok.example/bad.json"))
        assertTrue(catalog.blocks("evil.example", "https://evil.example/w.json"))
        assertTrue(catalog.blocks("api.evil.example", "https://api.evil.example/w.json"))
        assertFalse(catalog.blocks("notevil.example", "https://notevil.example/w.json"))
        assertTrue(catalog.blocks("ok.example", "https://ok.example/bad.json"))
        assertFalse(catalog.blocks("ok.example", "https://ok.example/good.json"))
    }

    @Test
    fun linkOnlyServicesRemainReviewedInTheWireCatalog() = runTest {
        val service = CatalogService(
            CatalogSource(
                listOf(
                    CatalogSourceEntry(
                        "claro",
                        "mobile",
                        "https://claro.example/a.json",
                        discoverable = false
                    )
                )
            ),
            definitions = { DefinitionCheck.Valid("{}") },
            log = {}
        )
        service.refresh()
        val entry = service.current().entries.single()
        assertFalse(entry.discoverable)
        assertEquals("{}", entry.definition)
        assertEquals(setOf("claro"), service.entryIds)
    }

    @Test
    fun servesTheLastValidDefinitionAndWithdrawsRejectedOnes() = runTest {
        var check: DefinitionCheck = DefinitionCheck.NotResponding
        val service = CatalogService(
            CatalogSource(
                listOf(CatalogSourceEntry("claro", "mobile", "https://claro.example/a.json"))
            ),
            definitions = { check },
            log = {}
        )
        service.refresh()
        assertNull(service.current().entries.single().definition)

        check = DefinitionCheck.Valid("""{"spec":"rayl-widget/1"}""")
        service.refresh()
        check = DefinitionCheck.NotResponding
        service.refresh()
        assertEquals("""{"spec":"rayl-widget/1"}""", service.current().entries.single().definition)

        check = DefinitionCheck.Rejected("HTTP 410")
        service.refresh()
        assertNull(service.current().entries.single().definition)
        assertEquals(setOf("claro"), service.entryIds)
    }
}
