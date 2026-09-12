package xyz.lilsus.blip.feature.payment

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import xyz.lilsus.blip.integration.blink.BlinkFundingWallet
import xyz.lilsus.blip.integration.blink.BlinkWalletCurrency

class PaymentPhaseStateTest {
    @Test
    fun replacingAndResettingTasksCancelWorkAndInvalidateOldResults() = runTest {
        val tasks = PaymentTaskRegistry(backgroundScope)
        lateinit var firstToken: PaymentTaskToken
        lateinit var secondToken: PaymentTaskToken

        val firstJob =
            tasks.launchReplacing("payment") { token ->
                firstToken = token
                awaitCancellation()
            }
        testScheduler.runCurrent()

        val secondJob =
            tasks.launchReplacing("payment") { token ->
                secondToken = token
                awaitCancellation()
            }
        testScheduler.runCurrent()

        assertTrue(firstJob.isCancelled)
        assertFalse(tasks.isCurrent(firstToken))
        assertFailsWith<CancellationException> { firstToken.ensureCurrent() }
        assertTrue(tasks.isCurrent(secondToken))

        tasks.reset()

        assertTrue(secondJob.isCancelled)
        assertFalse(tasks.isCurrent(secondToken))
        assertFailsWith<CancellationException> { secondToken.ensureCurrent() }
    }

    @Test
    fun staleAdmissionCompletionCannotClearANewerAdmission() = runTest {
        val registry = PaymentTaskRegistry(backgroundScope)
        val staleToken = PaymentTaskToken(registry, 0L, "stale")
        val currentToken = PaymentTaskToken(registry, 0L, "current")
        val admission = PaymentAdmissionSession()

        assertTrue(admission.begin(staleToken))
        admission.reset()
        assertTrue(admission.begin(currentToken))

        admission.complete(staleToken)

        assertEquals(
            PaymentAdmissionState.Admitting(currentToken),
            admission.state
        )
    }

    @Test
    fun confirmationSessionHoldsExactlyOneTypedPendingAction() = runTest {
        val registry = PaymentTaskRegistry(backgroundScope)
        val token = PaymentTaskToken(registry, 0L, "confirmation")
        val session = PaymentConfirmationSession()
        val pending =
            ExecutablePayment(
                invoice = invoice(),
                amountOverrideMsats = null,
                fundingWallet = TEST_FUNDING_WALLET,
                fundingAmountCents = null,
                origin = PendingOrigin.Invoice,
                dynamicSourceKey = null,
                targetContext = null,
                replacesDynamicGuardId = null
            )

        assertTrue(session.begin(token))
        assertTrue(session.await(token, pending))
        assertFalse(session.begin(token))
        assertSame(pending, session.take())
        assertEquals(PaymentConfirmationState.Idle, session.state)
    }

    private fun invoice(): Bolt11Invoice = Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = 5_000L.msat,
        paymentHash = Crypto.sha256("payment".encodeToByteArray()).toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 1 }),
        description = Either.Left("test"),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features =
            Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
        timestampSeconds = 1L
    )
}

private val TEST_FUNDING_WALLET =
    BlinkFundingWallet("wallet-btc", BlinkWalletCurrency.BTC)
