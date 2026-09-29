package xyz.lilsus.raylsuite.core.widgetspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.widgetspec.WidgetFixtures.CLARO_URL
import xyz.lilsus.raylsuite.core.widgetspec.WidgetFixtures.claro
import xyz.lilsus.raylsuite.core.widgetspec.WidgetFixtures.edited
import xyz.lilsus.raylsuite.core.widgetspec.WidgetProblemReason as Reason

class WidgetDefinitionParserTest {
    @Test
    fun productCatalogueParses() {
        val definition = valid(CLARO_URL, claro)
        assertEquals("lab.example", definition.host)
        assertEquals("Claro El Salvador", definition.displayName)
        val products = assertIs<WidgetPurchase.Product>(definition.purchase).products
        assertEquals(StoredAmount(500, "USD"), products[1].price)
        assertEquals(false, products[2].available)
        assertEquals("SV", definition.inputs.single().country)
        // Terms may live on another origin; action URLs may not.
        assertEquals("paquetes2.miclaro.com.sv", definition.terms?.url?.host)
        // Unknown families and default items that do not exist are ignored.
        assertEquals(listOf(WidgetFamily.Small, WidgetFamily.Medium), definition.supportedFamilies)
        assertEquals(listOf("p-1d", "p-7d"), definition.families.getValue(WidgetFamily.Medium).products)
        // Providers may name a tile per size; small tiles need a short name.
        assertEquals("Claro", definition.families.getValue(WidgetFamily.Small).label)
        assertNull(definition.families.getValue(WidgetFamily.Medium).label)
    }

    @Test
    fun inputsAreOptional() {
        val definition = valid(CLARO_URL, edited(claro) { remove("inputs") })
        assertEquals(emptyList(), definition.inputs)
    }

    @Test
    fun familiesAreRequired() {
        assertProblems(parse(edited(claro) { remove("families") }), "families" to Reason.Missing)
    }

    @Test
    fun amountPurchaseWithSecretInputAndStatus() {
        val definition = valid(WidgetFixtures.PPQ_URL, WidgetFixtures.ppq)
        val range = assertIs<WidgetPurchase.Amount>(definition.purchase).range
        assertEquals(10L, range.min.minor)
        assertEquals(1L, range.stepMinor)
        assertEquals(listOf(500L, 1_000L, 2_000L), range.presets.map { it.minor })
        assertTrue(definition.inputs.single().secret)
        assertEquals("/rayl/status", definition.statusUrl?.value?.removePrefix(WidgetFixtures.BASE))
    }

    @Test
    fun statusOnlyDefinition() {
        val definition = valid(WidgetFixtures.PRICE_URL, WidgetFixtures.price)
        assertNull(definition.purchase)
        assertNull(definition.invoiceUrl)
        assertEquals("\$64,000", definition.snapshot?.status?.value)
    }

    @Test
    fun statusWidgetsNeedASnapshot() {
        assertProblems(
            parse(edited(WidgetFixtures.price) { remove("snapshot") }),
            "snapshot" to Reason.Missing
        )
        assertProblems(
            parse(
                edited(WidgetFixtures.price) {
                    put("snapshot", buildJsonObject { putJsonObject("status") { put("title", "BTC") } })
                }
            ),
            "snapshot.status.value" to Reason.Missing
        )
    }

    @Test
    fun brandIsReadAndInvalidBrandIsDropped() {
        fun brand(brand: String) = valid(
            CLARO_URL,
            edited(claro) { put("brand", WidgetProtocol.json.parseToJsonElement(brand)) }
        ).brand
        assertEquals(
            WidgetBrand("Claro", PNG, "#DA291C"),
            brand("""{"name":"Claro","icon":"data:image/png;base64,$PNG","tint":"#da291c"}""")
        )
        assertEquals(
            WidgetBrand("Claro", null, "#DA291C"),
            brand("""{"name":"Claro","icon":"data:image/jpeg;base64,$PNG","tint":"#DA291C"}""")
        )
        assertNull(brand("""{"icon":"data:image/png;base64,bm90IGEgcG5n","tint":"red"}"""))
    }

    @Test
    fun deferredPurchaseAndInputTypesAreRefused() {
        assertProblems(
            parse(
                edited(claro) {
                    put("purchase", WidgetProtocol.json.parseToJsonElement("""{"type":"lookup","currency":"USD"}"""))
                }
            ),
            "purchase.type" to Reason.UnsupportedPurchase
        )
        assertProblems(
            parse(
                edited(claro) {
                    put(
                        "inputs",
                        WidgetProtocol.json.parseToJsonElement(
                            """[{"id":"biller","type":"choice","label":"Distributor","options":[{"value":"caess","label":"CAESS"}]}]"""
                        )
                    )
                }
            ),
            "inputs[0].type" to Reason.UnsupportedInput
        )
    }

    @Test
    fun unknownSemanticCapabilitiesStopEvenOtherwiseSupportedProducts() {
        fun requiring(value: String) = parse(
            edited(claro) {
                put("requires", WidgetProtocol.json.parseToJsonElement(value))
            }
        )
        assertIs<WidgetDefinitionResult.Valid>(requiring("[]"))
        assertProblems(requiring("[\"voucher-delivery\"]"), "requires[0]" to Reason.UnsupportedCapability)
        assertProblems(requiring("[42]"), "requires[0]" to Reason.WrongType)
        assertProblems(requiring("[\"\"]"), "requires[0]" to Reason.InvalidFormat)
        assertProblems(requiring("[\"Voucher\"]"), "requires[0]" to Reason.InvalidFormat)
        assertProblems(requiring("null"), "requires" to Reason.Missing)
        assertProblems(requiring("{}"), "requires" to Reason.WrongType)
        // Additive display metadata may still be ignored safely.
        assertIs<WidgetDefinitionResult.Valid>(parse(edited(claro) { put("x-display-hint", JsonPrimitive("extra")) }))
    }

    @Test
    fun widgetsWithoutStatusNeedNoSnapshot() {
        assertNull(valid(WidgetFixtures.CLARO_URL, WidgetFixtures.claro).snapshot)
    }

    @Test
    fun otherVersionsAndUnknownPurchasesAreRefused() {
        assertProblems(
            parse(edited(claro) { put("spec", JsonPrimitive("rayl-widget/0.1")) }),
            "spec" to Reason.UnsupportedSpec
        )
        assertProblems(
            parse(claro.replace("\"type\":\"product\"", "\"type\":\"subscription\"")),
            "purchase.type" to Reason.UnsupportedPurchase
        )
    }

    @Test
    fun actionsMustStayOnTheDefinitionOrigin() {
        assertProblems(
            parse(edited(claro) { put("invoiceUrl", JsonPrimitive("https://evil.example/invoice")) }),
            "invoiceUrl" to Reason.OtherHost
        )
        assertProblems(
            parse(edited(claro) { put("invoiceUrl", JsonPrimitive("http://lab.example/invoice")) }),
            "invoiceUrl" to Reason.NotHttps
        )
    }

    @Test
    fun purchasesNeedEndpointsAndStatusOnlyMustNotAdvertiseThem() {
        assertProblems(
            parse(
                edited(claro) {
                    remove("deliveryUrl")
                    remove("terms")
                }
            ),
            // Terms are optional in v1; the endpoints are not.
            "deliveryUrl" to Reason.Missing
        )
        assertProblems(
            WidgetDefinitionParser.parse(
                WidgetFixtures.PRICE_URL,
                edited(WidgetFixtures.price) { put("invoiceUrl", JsonPrimitive("${WidgetFixtures.BASE}/rayl/invoice")) }
            ),
            "invoiceUrl" to Reason.Unexpected
        )
    }

    @Test
    fun spoofingTextAndBadMoneyAreRefused() {
        assertProblems(
            parse(edited(claro) { put("title", JsonPrimitive("Claro‮")) }),
            "title" to Reason.UnsafeText
        )
        assertIs<WidgetDefinitionResult.Invalid>(parse(claro.replace("\"5.00\"", "\"5\"")))
        assertProblems(
            parse(claro.replace("\"currency\":\"USD\"},\"validity\":\"7", "\"currency\":\"SVC\"},\"validity\":\"7")),
            "purchase.products[1].price" to Reason.UnsupportedCurrency
        )
    }

    private fun parse(body: String) = WidgetDefinitionParser.parse(CLARO_URL, body)

    private fun valid(url: String, body: String): WidgetDefinition {
        val result = WidgetDefinitionParser.parse(url, body)
        return assertIs<WidgetDefinitionResult.Valid>(result, "$result").definition
    }

    private fun assertProblems(result: WidgetDefinitionResult, vararg expected: Pair<String, Reason>) {
        val invalid = assertIs<WidgetDefinitionResult.Invalid>(result)
        assertEquals(expected.map { WidgetProblem(it.first, it.second) }.toSet(), invalid.problems.toSet())
    }

    private companion object {
        const val PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
