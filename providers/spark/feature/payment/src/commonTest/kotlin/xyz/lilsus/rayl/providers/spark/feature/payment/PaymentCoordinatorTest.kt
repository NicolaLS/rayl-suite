package xyz.lilsus.rayl.providers.spark.feature.payment

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.rayl.providers.spark.application.payment.DefaultPaymentEngine
import xyz.lilsus.rayl.providers.spark.application.payment.InvoiceFingerprint
import xyz.lilsus.rayl.providers.spark.application.payment.LnurlPayRequestPayload
import xyz.lilsus.rayl.providers.spark.application.payment.LnurlPayReviewDetails
import xyz.lilsus.rayl.providers.spark.application.payment.ParsedSdkInput
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentMethod
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentNetwork
import xyz.lilsus.rayl.providers.spark.application.payment.PreparedSdkPayload
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPayment
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPaymentStatus
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPreparationResult
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPreparedPayment
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.createPaymentLinkInbox
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.Satoshi
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDraft
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.LocalHubWidgets
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentCoordinatorTest {
    @Test
    fun rescanningPaidInvoiceOpensOriginalDetailWithoutNewPaymentOrHaptic() = runTest {
        val harness = harness()
        harness.client.preimage = "ab".repeat(32)
        val coordinator = harness.coordinator
        coordinator.dispatch(PaymentIntent.QrCodeScanned("invoice"))
        runCurrent()
        assertIs<PaymentUiState.Confirm>(coordinator.uiState.value)
        coordinator.dispatch(PaymentIntent.ConfirmPaymentSubmit)
        runCurrent()
        assertIs<PaymentUiState.Success>(coordinator.uiState.value)
        val original = coordinator.sessionTransactions.value.single()
        assertEquals(harness.client.preimage, original.receipt?.preimage)
        assertEquals(1, coordinator.newSessionTransactionCount.value)
        coordinator.dispatch(PaymentIntent.DismissResult)
        coordinator.dispatch(PaymentIntent.QrCodeScanned("invoice"))
        runCurrent()

        assertEquals(PaymentUiState.Active, coordinator.uiState.value)
        assertEquals(original.id, coordinator.transactionDetailNavigationTarget.value)
        assertEquals(listOf(original), coordinator.sessionTransactions.value)
        assertEquals(0, coordinator.newSessionTransactionCount.value)
        assertEquals(1, harness.client.sendCount)
        assertEquals(1, harness.haptics.scans)
        assertEquals(1, harness.haptics.payments)
        coordinator.dispatch(PaymentIntent.TransactionDetailNavigationHandled("other"))
        assertEquals(original.id, coordinator.transactionDetailNavigationTarget.value)
        coordinator.dispatch(PaymentIntent.TransactionDetailNavigationHandled(original.id))
        assertNull(coordinator.transactionDetailNavigationTarget.value)
        coordinator.clear()
    }

    @Test
    fun unknownScansAreIgnoredAndUnsupportedFormatsOnlyToast() = runTest {
        val harness = harness()
        val events = mutableListOf<PaymentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.events.collect { events += it }
        }
        val cases = listOf(
            LightningInputParser.FailureReason.Empty to null,
            LightningInputParser.FailureReason.Unrecognized to null,
            LightningInputParser.FailureReason.BitcoinAddress to
                PaymentToastMessage.BitcoinAddressNotSupported,
            LightningInputParser.FailureReason.Bolt12 to PaymentToastMessage.Bolt12NotSupported,
            LightningInputParser.FailureReason.UnsupportedLnurl to
                PaymentToastMessage.LnurlRequestNotSupported,
            LightningInputParser.FailureReason.UnsupportedDeepLink to
                PaymentToastMessage.PaymentLinkNotSupported
        )
        for ((failure, toast) in cases) {
            events.clear()
            harness.client.parsedInput = ParsedSdkInput.InvalidInput(failure)
            harness.coordinator.dispatch(PaymentIntent.QrCodeScanned("input"))
            runCurrent()
            assertEquals(PaymentUiState.Active, harness.coordinator.uiState.value)
            assertEquals<List<PaymentEvent>>(listOfNotNull(toast?.let { PaymentEvent.ShowToast(it) }), events)
        }
        assertEquals(0, harness.haptics.scans)
        assertEquals(0, harness.client.sendCount)
        assertTrue(harness.coordinator.sessionTransactions.value.isEmpty())
        harness.coordinator.clear()
    }

    @Test
    fun malformedPaymentShowsErrorWithoutScanHaptic() = runTest {
        val harness = harness()
        harness.client.parsedInput = ParsedSdkInput.InvalidInput(
            LightningInputParser.FailureReason.InvalidInvoice("Invalid Spark invoice")
        )
        harness.coordinator.dispatch(PaymentIntent.QrCodeScanned("spark1invalid"))
        runCurrent()
        assertIs<PaymentUiState.Error>(harness.coordinator.uiState.value)
        assertEquals(0, harness.haptics.scans)
        assertEquals(0, harness.client.sendCount)
        harness.coordinator.clear()
    }

    @Test
    fun sparkInvoiceKeepsItsSupportedPaymentFlow() = runTest {
        val harness = harness()
        harness.client.parsedInput = ParsedSdkInput.SparkInvoice(
            invoice = "spark-invoice",
            amountSats = 21,
            network = PaymentNetwork.REGTEST,
            expiryTimeEpochSeconds = 200uL,
            tokenIdentifier = null,
            senderPublicKey = null
        )
        harness.client.method = PaymentMethod.SPARK_INVOICE
        harness.coordinator.dispatch(PaymentIntent.QrCodeScanned("spark-invoice"))
        runCurrent()
        assertIs<PaymentUiState.Confirm>(harness.coordinator.uiState.value)
        assertEquals(1, harness.haptics.scans)
        harness.coordinator.dispatch(PaymentIntent.ConfirmPaymentSubmit)
        runCurrent()
        assertNull(harness.coordinator.sessionTransactions.value.single().receipt?.preimage)
        harness.coordinator.clear()
    }

    @Test
    fun hubShortcutCommentReachesLnurlPreparation() = runTest {
        val harness = harness()
        val contact = harness.repository.saveContact(
            assertNotNull(LightningAddress.parse("alice@example.com")),
            "Alice"
        )
        val widget = assertNotNull(
            harness.repository.saveWidget(
                HubWidgetDraft(
                    "local.shortcut",
                    HubWidgetKind.Shortcut,
                    LocalHubWidgets.Single,
                    contactIds = listOf(contact.id),
                    amount = StoredAmount(21, "SAT"),
                    comment = "Thanks!"
                )
            )
        )
        harness.client.parsedInput = ParsedSdkInput.LnurlPay(
            requestFingerprint = InvoiceFingerprint.lnurl("alice@example.com"),
            minSendableMsat = 1_000uL,
            maxSendableMsat = 100_000uL,
            reviewDetails = LnurlPayReviewDetails("example.com", "A payment", null, null),
            payload = object : LnurlPayRequestPayload {},
            commentAllowed = 7
        )
        harness.hub.dispatch(PaymentHubIntent.SelectItem(assertNotNull(widget.targetId)))
        runCurrent()
        assertEquals<List<String?>>(listOf("Thanks!"), harness.client.comments)
        assertIs<PaymentUiState.Confirm>(harness.coordinator.uiState.value)
        harness.coordinator.clear()
    }

    private suspend fun TestScope.harness(): Harness {
        val client = TestClient()
        val engine = DefaultPaymentEngine(AppEnvironment.DEBUG, nowEpochSeconds = { 100L })
        engine.attach(client)
        val settings = MapSettings()
        val preferences = DefaultPaymentPreferencesRepository(settings)
        preferences.setConfirmationMode(PaymentConfirmationMode.Always)
        val currencyPreferences = DefaultCurrencyPreferences(settings)
        currencyPreferences.setCode("SAT")
        val repository = DefaultPaymentHubRepository(settings)
        val hub = PaymentHubController(repository, backgroundScope)
        val haptics = TestHaptics()
        val coordinator = PaymentCoordinator(
            engine = engine,
            paymentLinks = createPaymentLinkInbox(),
            bitcoinPriceProvider = BitcoinPriceProvider { null },
            currencyPreferences = currencyPreferences,
            paymentPreferences = preferences,
            paymentHub = hub,
            haptics = haptics,
            coroutineContext = StandardTestDispatcher(testScheduler)
        )
        runCurrent()
        return Harness(coordinator, client, haptics, repository, hub)
    }

    private data class Harness(
        val coordinator: PaymentCoordinator,
        val client: TestClient,
        val haptics: TestHaptics,
        val repository: DefaultPaymentHubRepository,
        val hub: PaymentHubController
    )

    private class TestHaptics : HapticFeedbackManager {
        var scans = 0
        var payments = 0
        override fun notifyScanSuccess() {
            scans += 1
        }
        override fun notifyPaymentSuccess() {
            payments += 1
        }
    }

    private class TestClient : SparkPaymentClient {
        var parsedInput: ParsedSdkInput = ParsedSdkInput.Bolt11(
            "invoice",
            "hash",
            21_000uL,
            PaymentNetwork.REGTEST,
            200uL
        )
        var method = PaymentMethod.BOLT11
        var sendCount = 0
        var preimage: String? = null
        val comments = mutableListOf<String?>()

        override suspend fun parse(input: String): ParsedSdkInput = parsedInput
        override suspend fun identityPublicKey(): String = "identity"
        override suspend fun prepare(input: String, amountSats: Satoshi?): SdkPreparationResult =
            prepared(amountSats ?: Satoshi.positive(21))

        override suspend fun prepareLnurl(request: ParsedSdkInput.LnurlPay, amountSats: Satoshi, comment: String?): SdkPreparationResult {
            comments += comment
            return prepared(amountSats)
        }

        private fun prepared(amount: Satoshi) = SdkPreparationResult.Prepared(
            SdkPreparedPayment(
                payload = object : PreparedSdkPayload {},
                method = method,
                amountSats = amount,
                feeSats = Satoshi.nonNegative(1),
                tokenIdentifier = null,
                hasConversion = false,
                resolvedInvoiceFingerprint = InvoiceFingerprint.bolt11("hash"),
                resolvedNetwork = PaymentNetwork.REGTEST,
                resolvedExpiresAtEpochSeconds = 200uL
            )
        )

        override suspend fun send(prepared: SdkPreparedPayment, idempotencyKey: String): SdkPayment {
            sendCount += 1
            return SdkPayment("payment", SdkPaymentStatus.COMPLETED, preimage)
        }

        override suspend fun getPayment(paymentId: String): SdkPayment = error("Not used")
        override suspend fun observePayments(onPayment: (SdkPayment) -> Unit): suspend () -> Unit = error("Not used")
    }
}
