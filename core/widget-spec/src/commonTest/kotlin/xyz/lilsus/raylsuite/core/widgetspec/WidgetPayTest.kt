package xyz.lilsus.raylsuite.core.widgetspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import xyz.lilsus.raylsuite.core.model.StoredAmount

class WidgetPayTest {
    private val twelveDollars = StoredAmount(1_200, "USD")

    // At $100,000 per bitcoin, $12.00 is 12,000 sats.
    private val rate = 100_000.0

    @Test
    fun satPricesMustMatchExactly() {
        val price = StoredAmount(21_000, "SAT")
        assertEquals(WidgetPriceVerdict.Matches, checkWidgetInvoice(price, 21_000_000, null).verdict)
        assertEquals(WidgetPriceVerdict.Mismatch, checkWidgetInvoice(price, 21_001_000, null).verdict)
        assertEquals(WidgetPriceVerdict.Mismatch, checkWidgetInvoice(price, 21_000_500, null).verdict)
    }

    @Test
    fun fiatPricesAreCheckedWithRaylsRate() {
        fun verdict(sats: Long) = checkWidgetInvoice(twelveDollars, sats * 1_000, rate).verdict
        assertEquals(WidgetPriceVerdict.Matches, verdict(12_000))
        assertEquals(WidgetPriceVerdict.Matches, verdict(12_110))
        assertEquals(WidgetPriceVerdict.Higher, verdict(12_150))
        assertEquals(WidgetPriceVerdict.Higher, verdict(13_200))
        assertEquals(WidgetPriceVerdict.Mismatch, verdict(13_300))
        assertEquals(WidgetPriceVerdict.Lower, verdict(11_500))
        assertEquals(WidgetPriceVerdict.Mismatch, verdict(10_000))
        assertEquals(WidgetPriceVerdict.Mismatch, verdict(50_000))

        val warning = checkWidgetInvoice(twelveDollars, 12_150_000, rate)
        assertEquals(StoredAmount(1_215, "USD"), warning.invoiced)
    }

    @Test
    fun fiatPricesWithoutARateAreNeverPayable() {
        val check = checkWidgetInvoice(twelveDollars, 12_000_000, null)
        assertEquals(WidgetPriceVerdict.Unverifiable, check.verdict)
        assertEquals(false, check.payable)
    }

    @Test
    fun invoiceRequestCarriesTheAttempt() {
        assertEquals(
            """{"spec":"rayl-widget/1","widgetId":"ppq-credits","requestId":"req-0123456789abcdefghijk",""" +
                """"amount":{"amount":"5.00","currency":"USD"},""" +
                """"inputs":{"apiKey":"k"}}""",
            WidgetInvoiceRequest(
                widgetId = "ppq-credits",
                requestId = "req-0123456789abcdefghijk",
                amount = StoredAmount(500, "USD"),
                inputs = mapOf("apiKey" to "k")
            ).toJson()
        )
        assertEquals(22, newWidgetRequestId().length)
    }

    @Test
    fun issuedInvoiceNeedsOrderCredentialsAndPrice() {
        val issued = parseWidgetInvoiceResponse(
            200,
            """{"invoice":"lnbc1","orderId":"order-1","orderToken":"rF8kP2xW7nH4mT9aL1vC6q",""" +
                """"price":{"amount":"5.00","currency":"USD"}}"""
        )
        assertEquals(StoredAmount(500, "USD"), assertIs<WidgetInvoiceResult.Issued>(issued).price)
        assertEquals(
            WidgetInvoiceResult.NotResponding,
            parseWidgetInvoiceResponse(200, """{"invoice":"lnbc1","orderId":"o","orderToken":"rF8kP2xW7nH4mT9aL1vC6q"}""")
        )
        // An unguessable orderId needs no separate token.
        assertNull(
            assertIs<WidgetInvoiceResult.Issued>(
                parseWidgetInvoiceResponse(200, """{"invoice":"lnbc1","orderId":"o-7Kq","price":{"amount":"1.00","currency":"USD"}}""")
            ).orderToken
        )
        assertEquals(
            WidgetInvoiceResult.NothingDue("Nothing is due."),
            parseWidgetInvoiceResponse(200, """{"payable":false,"message":"Nothing is due."}""")
        )
    }

    @Test
    fun refusalsKeepSafeMessagesOnly() {
        assertEquals(
            WidgetInvoiceResult.Refused(WidgetErrorCode.ProductUnavailable, null, null),
            parseWidgetInvoiceResponse(409, """{"error":"product_unavailable"}""")
        )
        // Unknown codes are understood by their HTTP status.
        assertEquals(
            WidgetErrorCode.Unavailable,
            assertIs<WidgetInvoiceResult.Refused>(parseWidgetInvoiceResponse(503, """{"error":"new_thing"}""")).code
        )
        assertEquals(WidgetErrorCode.RateLimited, assertIs<WidgetInvoiceResult.Refused>(parseWidgetInvoiceResponse(429, "")).code)
        assertEquals(
            WidgetValidateResult.Valid("Account: Juan P••••"),
            parseWidgetValidateResponse(200, """{"ok":true,"message":"Account: Juan P••••"}""")
        )
        val spoofed = parseWidgetInvoiceResponse(409, """{"error":"product_unavailable","message":"Pay ‮evil"}""")
        assertNull(assertIs<WidgetInvoiceResult.Refused>(spoofed).message)
        assertEquals(
            WidgetErrorCode.Unavailable,
            assertIs<WidgetInvoiceResult.Refused>(parseWidgetInvoiceResponse(500, "<html>oops</html>")).code
        )
    }

    @Test
    fun deliveryReportsItemsAndExpiry() {
        val report = parseWidgetDeliveryResponse(
            200,
            """{"state":"delivered","items":[{"kind":"code","label":"Code","value":"UBR7"},{"kind":"barcode","label":"B","value":"1"}]}"""
        )
        val delivered = assertIs<WidgetDeliveryResult.Report>(report)
        // A barcode without a format the host can draw is kept as a plain code.
        assertEquals(listOf(WidgetDeliveryItemKind.Code, WidgetDeliveryItemKind.Code), delivered.items.map { it.kind })
        assertEquals(
            WidgetDeliveryState.Expired,
            assertIs<WidgetDeliveryResult.Report>(parseWidgetDeliveryResponse(200, """{"state":"expired"}""")).state
        )
        assertEquals(WidgetDeliveryResult.NotFound, parseWidgetDeliveryResponse(404, "{}"))
    }

    @Test
    fun statusKeepsTypedValueAndObservation() {
        val status = parseWidgetStatusResponse(
            200,
            """{"title":"Bitcoin price","value":"$83,234.00","typedValue":{"type":"money","amount":"83234.00","currency":"USD"},""" +
                """"asOf":"2026-09-28T12:00:00.000Z","observedAt":"2026-09-28T12:01:00.000Z","refreshAfterSeconds":60}"""
        )
        assertEquals(WidgetTypedValue.Money("83234.00", "USD"), status?.typedValue)
        assertEquals(60, status?.refreshAfterSeconds)
        assertNull(parseWidgetStatusResponse(200, """{"value":"x"}"""))
    }

    @Test
    fun deliveryLinksMustBeStrictHttps() {
        val report = assertIs<WidgetDeliveryResult.Report>(
            parseWidgetDeliveryResponse(
                200,
                """{"state":"delivered","items":[""" +
                    """{"kind":"link","label":"Voucher","value":"https://c.oky.app/c/abc?x=1"},""" +
                    """{"kind":"link","label":"Bad","value":"javascript:alert(1)"}]}"""
            )
        )
        assertEquals(
            listOf(WidgetDeliveryItemKind.Link, WidgetDeliveryItemKind.Text),
            report.items.map { it.kind }
        )
    }

    @Test
    fun vouchersCarryNativeCredentialsAndBarcodes() {
        val report = assertIs<WidgetDeliveryResult.Report>(
            parseWidgetDeliveryResponse(
                200,
                """{"state":"delivered","presentation":"voucher","items":[""" +
                    """{"kind":"code","label":"Code","value":"OKY26"},""" +
                    """{"kind":"pin","label":"PIN","value":"1234"},""" +
                    """{"kind":"barcode","label":"Barcode","value":"0615","format":"code128"},""" +
                    """{"kind":"barcode","label":"Odd","value":"99","format":"maxicode"}]}"""
            )
        )
        assertEquals(WidgetDeliveryPresentation.Voucher, report.presentation)
        assertEquals(
            listOf(
                WidgetDeliveryItemKind.Code,
                WidgetDeliveryItemKind.Pin,
                WidgetDeliveryItemKind.Barcode,
                WidgetDeliveryItemKind.Code
            ),
            report.items.map { it.kind }
        )
        assertEquals(WidgetBarcodeFormat.Code128, report.items[2].format)
        assertNull(report.items[3].format)
        assertEquals(
            WidgetDeliveryPresentation.Receipt,
            assertIs<WidgetDeliveryResult.Report>(parseWidgetDeliveryResponse(200, """{"state":"pending"}""")).presentation
        )
    }

    @Test
    fun statusTrendIsKeptOnlyWhenWellFormed() {
        fun status(trend: String) = parseWidgetStatusResponse(
            200,
            """{"value":"1","asOf":"2026-09-28T12:00:00.000Z","trend":$trend}"""
        )
        assertEquals(
            WidgetTrend(listOf(1.0, 2.5, 2.0), "24h"),
            status("""{"points":[1,2.5,2],"period":"24h"}""")?.trend
        )
        assertNull(status("""{"points":[1]}""")?.trend)
        assertNull(status("""{"points":[1,"2"]}""")?.trend)
        assertNull(status("""{"points":${(0..96).toList()}}""")?.trend)
    }
}
