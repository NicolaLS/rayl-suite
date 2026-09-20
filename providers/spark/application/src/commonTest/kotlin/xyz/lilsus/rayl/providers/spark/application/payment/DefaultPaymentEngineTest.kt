package xyz.lilsus.rayl.providers.spark.application.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.raylsuite.core.model.Satoshi

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPaymentEngineTest {
    @Test
    fun pendingSubmissionWaitsForItsExactSdkPaymentAndBlocksDetach() = runTest {
        val client = TestClient()
        val engine = engine(client)
        val handle = engine.prepareInvoice()
        val submission = async { engine.confirm(handle) }
        runCurrent()
        assertFalse(submission.isCompleted)

        val detachment = async { engine.detach() }
        client.emit(SdkPayment("unrelated-payment", SdkPaymentStatus.COMPLETED))
        client.emit(SdkPayment("sdk-payment", SdkPaymentStatus.PENDING))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("sdk-payment"), client.lookups)
        assertFalse(submission.isCompleted)
        assertFalse(detachment.isCompleted)

        client.emit(SdkPayment("sdk-payment", SdkPaymentStatus.COMPLETED))
        val receipt = assertIs<ConfirmPaymentResult.Completed>(submission.await()).receipt
        assertEquals(PaymentOutcome.COMPLETED, receipt.outcome)
        assertFalse(client.isObserving)
        detachment.await()
        assertEquals(1, client.sendCount)
        assertIs<PreparePaymentResult.WalletUnavailable>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
    }

    @Test
    fun lookupFailureReturnsUncertaintyWithoutRecordingAPaidInvoice() = runTest {
        val client = TestClient().apply {
            lookupResult = { error("unavailable") }
        }
        val engine = engine(client)
        assertIs<ConfirmPaymentResult.OutcomeUnknown>(engine.confirm(engine.prepareInvoice()))
        assertIs<PreparePaymentResult.Ready>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
        assertEquals(1, client.sendCount)
        assertFalse(client.isObserving)
    }

    @Test
    fun completedInvoiceIsRecognizedWithoutAnotherSubmission() = runTest {
        val client = TestClient().apply {
            sendResult = { SdkPayment("sdk-payment", SdkPaymentStatus.COMPLETED) }
        }
        val engine = engine(client)
        val result = assertIs<ConfirmPaymentResult.Completed>(engine.confirm(engine.prepareInvoice()))
        val existing = assertIs<PreparePaymentResult.Existing>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
        assertEquals(result.receipt, existing.receipt)
        assertEquals(1, client.sendCount)
        assertEquals(0, client.observationCount)
    }

    @Test
    fun completionBeforeSubscriptionIsFoundByTheInitialLookup() = runTest {
        val client = TestClient().apply {
            lookupResult = { SdkPayment("sdk-payment", SdkPaymentStatus.COMPLETED) }
        }
        val engine = engine(client)
        val receipt = assertIs<ConfirmPaymentResult.Completed>(
            engine.confirm(engine.prepareInvoice())
        ).receipt
        assertEquals(PaymentOutcome.COMPLETED, receipt.outcome)
        assertEquals(listOf("sdk-payment"), client.lookups)
        assertFalse(client.isObserving)
    }

    @Test
    fun terminalEventDuringFailedLookupStillResolvesThePayment() = runTest {
        val client = TestClient().apply {
            lookupResult = {
                emit(SdkPayment("sdk-payment", SdkPaymentStatus.COMPLETED))
                error("unavailable")
            }
        }
        val engine = engine(client)
        val receipt = assertIs<ConfirmPaymentResult.Completed>(
            engine.confirm(engine.prepareInvoice())
        ).receipt
        assertEquals(PaymentOutcome.COMPLETED, receipt.outcome)
        assertFalse(client.isObserving)
    }

    @Test
    fun failedEventProducesAResolvedReceiptWithoutCachingSuccess() = runTest {
        val client = TestClient()
        val engine = engine(client)
        val handle = engine.prepareInvoice()
        val submission = async { engine.confirm(handle) }
        runCurrent()
        client.emit(SdkPayment("sdk-payment", SdkPaymentStatus.FAILED))
        val receipt = assertIs<ConfirmPaymentResult.Completed>(submission.await()).receipt
        assertEquals(PaymentOutcome.FAILED, receipt.outcome)
        assertFalse(client.isObserving)
        assertIs<PreparePaymentResult.Ready>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
    }

    @Test
    fun cancellationWhileWaitingRemovesTheListener() = runTest {
        val client = TestClient()
        val engine = engine(client)
        val handle = engine.prepareInvoice()
        val submission = async { engine.confirm(handle) }
        runCurrent()
        assertTrue(client.isObserving)
        submission.cancel()
        submission.join()
        assertFalse(client.isObserving)
        assertIs<PreparePaymentResult.Ready>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
    }

    @Test
    fun cancellationDuringRegistrationStillRemovesTheListener() = runTest {
        val release = CompletableDeferred<Unit>()
        val client = TestClient().apply {
            afterRegistration = { release.await() }
        }
        val engine = engine(client)
        val handle = engine.prepareInvoice()
        val submission = async { engine.confirm(handle) }
        runCurrent()
        assertTrue(client.isObserving)
        submission.cancel()
        release.complete(Unit)
        submission.join()
        assertTrue(submission.isCancelled)
        assertFalse(client.isObserving)
        assertTrue(client.lookups.isEmpty())
    }

    @Test
    fun cancellationAfterNonCooperativeSdkReturnDoesNotRecordAReceipt() = runTest {
        val release = CompletableDeferred<Unit>()
        val client = TestClient().apply {
            sendResult = {
                withContext(NonCancellable) { release.await() }
                SdkPayment("sdk-payment", SdkPaymentStatus.COMPLETED)
            }
        }
        val engine = engine(client)
        val handle = engine.prepareInvoice()
        val submission = async { engine.confirm(handle) }
        runCurrent()
        submission.cancel()
        release.complete(Unit)
        submission.join()
        assertTrue(submission.isCancelled)
        assertFalse(client.isObserving)
        assertIs<PreparePaymentResult.Ready>(
            engine.prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
        )
    }

    private suspend fun engine(client: TestClient) = DefaultPaymentEngine(
        environment = AppEnvironment.DEBUG,
        nowEpochSeconds = { 100L },
        newPaymentId = { "receipt" }
    ).also { it.attach(client) }

    private suspend fun DefaultPaymentEngine.prepareInvoice(): PaymentDraftHandle = assertIs<PreparePaymentResult.Ready>(
        prepare("invoice", PaymentOrigin.DETECTED_CONTENT)
    ).payment.handle

    private class TestClient : SparkPaymentClient {
        var sendCount = 0
        val lookups = mutableListOf<String>()
        var observationCount = 0
        private var observer: ((SdkPayment) -> Unit)? = null
        val isObserving: Boolean get() = observer != null
        var afterRegistration: suspend () -> Unit = {}
        var lookupResult: suspend () -> SdkPayment = {
            SdkPayment("sdk-payment", SdkPaymentStatus.PENDING)
        }
        var sendResult: suspend () -> SdkPayment = {
            SdkPayment("sdk-payment", SdkPaymentStatus.PENDING)
        }

        override suspend fun parse(input: String): ParsedSdkInput = ParsedSdkInput.Bolt11(
            invoice = input,
            paymentHash = "test-hash",
            amountMsat = 21_000uL,
            network = PaymentNetwork.REGTEST,
            expiresAtEpochSeconds = 200uL
        )

        override suspend fun identityPublicKey(): String = "test-public-key"

        override suspend fun prepare(input: String, amountSats: Satoshi?): SdkPreparationResult = SdkPreparationResult.Prepared(
            SdkPreparedPayment(
                payload = TestPayload,
                method = PaymentMethod.BOLT11,
                amountSats = Satoshi.positive(21),
                feeSats = Satoshi.nonNegative(1),
                tokenIdentifier = null,
                hasConversion = false
            )
        )

        override suspend fun prepareLnurl(request: ParsedSdkInput.LnurlPay, amountSats: Satoshi): SdkPreparationResult = error("Not used")

        override suspend fun send(prepared: SdkPreparedPayment, idempotencyKey: String): SdkPayment {
            sendCount += 1
            return sendResult()
        }

        override suspend fun getPayment(paymentId: String): SdkPayment {
            lookups += paymentId
            return lookupResult()
        }

        override suspend fun observePayments(onPayment: (SdkPayment) -> Unit): suspend () -> Unit {
            check(observer == null)
            observer = onPayment
            observationCount += 1
            afterRegistration()
            return { observer = null }
        }

        fun emit(payment: SdkPayment) {
            observer?.invoke(payment)
        }
    }

    private data object TestPayload : PreparedSdkPayload
}
