package xyz.lilsus.rayl.providers.rayl.feature.payment

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.toByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCredit
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCreditOutcome
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCreditQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayOutcome
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayments
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuoteResult
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentRequestSource
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

class RaylPaymentSettlementTest {
    @Test
    fun quotesAmountlessInvoicesInWholeSats() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 3)))

        val prepared = RaylPaymentSettlement(pocket).prepare(payment(amountOverrideMsats = 21_000))

        assertIs<SettlementPreparation.Ready<RaylPayment>>(prepared)
        assertEquals(21L, pocket.quotedAmountSats)
    }

    @Test
    fun rejectsSubSatoshiAmountsBeforeQuoting() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 0)))

        val prepared = RaylPaymentSettlement(pocket).prepare(payment(amountOverrideMsats = 1_500))

        assertEquals(RaylPaymentError.SubSatoshiAmount, providerError(prepared))
        assertEquals(0, pocket.quotes)
    }

    @Test
    fun reportsMissingSpendingPowerBeforeConfirmation() = runTest {
        val prepared = RaylPaymentSettlement(FakePocket(PocketQuoteResult.InsufficientFunds))
            .prepare(payment())

        assertEquals(RaylPaymentError.InsufficientFunds, providerError(prepared))
    }

    @Test
    fun mapsPaymentOutcomes() = runTest {
        val quote = Quote(feeSats = 2)
        val pocket = FakePocket(PocketQuoteResult.Quoted(quote))
        val settlement = RaylPaymentSettlement(pocket)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<RaylPayment>

        pocket.outcome = PocketPayOutcome.Paid(feeSats = 2, preimage = "00")
        assertEquals(SettlementOutcome.Paid(2_000, "00"), settlement.settle(ready.payment))

        pocket.outcome = PocketPayOutcome.Unknown("pending")
        assertIs<SettlementOutcome.Unknown>(settlement.settle(ready.payment))

        pocket.outcome = PocketPayOutcome.Failed(null)
        assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
    }

    @Test
    fun clubCreditPaysFirst() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 1)))
        val credit = FakeCredit(ClubCreditQuote.Quoted(amountMsat = 21_000, feeMsat = 3_000))
        val settlement = RaylPaymentSettlement(pocket, credit)

        val ready = settlement.prepare(payment(amountOverrideMsats = 21_000))
            as SettlementPreparation.Ready<RaylPayment>
        assertEquals(0, pocket.quotes)
        assertEquals(21_000L, credit.quotedAmountMsat)

        credit.outcome = ClubCreditOutcome.Paid(feeMsat = 2_000, preimage = "00")
        assertEquals(SettlementOutcome.Paid(2_000, "00"), settlement.settle(ready.payment))
        assertEquals(3_000L, credit.spends.single().maxFeeMsat)

        // Confirming the same prepared payment again reuses its idempotency key.
        credit.outcome = ClubCreditOutcome.Unknown
        assertIs<SettlementOutcome.Unknown>(settlement.settle(ready.payment))
        credit.outcome = ClubCreditOutcome.Failed
        assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
        assertEquals(1, credit.spends.map { it.idempotencyKey }.distinct().size)
    }

    @Test
    fun thePocketPaysWhenCreditCannot() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 1)))
        val credit = FakeCredit(ClubCreditQuote.NotUsable)
        val settlement = RaylPaymentSettlement(pocket, credit)

        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<RaylPayment>
        assertEquals(1, pocket.quotes)

        pocket.outcome = PocketPayOutcome.Paid(feeSats = 1, preimage = "00")
        assertEquals(SettlementOutcome.Paid(1_000, "00"), settlement.settle(ready.payment))
        assertEquals(emptyList(), credit.spends)
    }

    @Test
    fun anInvoiceCreditRejectsIsNotTriedWithThePocket() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 0)))
        val settlement =
            RaylPaymentSettlement(pocket, FakeCredit(ClubCreditQuote.InvoiceRejected(expired = true)))

        val prepared = settlement.prepare(payment())

        assertEquals(
            PaymentUiError.InvalidInvoice("Invoice has expired"),
            (prepared as SettlementPreparation.Unavailable).error
        )
        assertEquals(0, pocket.quotes)
    }

    private fun providerError(preparation: SettlementPreparation<RaylPayment>) =
        ((preparation as SettlementPreparation.Unavailable).error as PaymentUiError.Provider).error

    private fun payment(amountOverrideMsats: Long? = null) = PreparedPayment(
        invoice = Bolt11Invoice.create(
            chain = Chain.Regtest,
            amount = null,
            paymentHash = Crypto.sha256("payment".encodeToByteArray()).toByteVector32(),
            privateKey = PrivateKey(ByteArray(32) { 1 }),
            description = Either.Left("test"),
            minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
            timestampSeconds = 1L
        ),
        amountOverrideMsats = amountOverrideMsats,
        source = PaymentRequestSource.Camera
    )
}

private class Quote(override val feeSats: Long) : PocketQuote

private class FakePocket(private val quoteResult: PocketQuoteResult) : PocketPayments {
    var quotes = 0
    var quotedAmountSats: Long? = null
    var outcome: PocketPayOutcome = PocketPayOutcome.Failed(null)

    override suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult {
        quotes++
        quotedAmountSats = amountSats
        return quoteResult
    }

    override suspend fun pay(quote: PocketQuote): PocketPayOutcome = outcome
}

private class FakeCredit(private val quote: ClubCreditQuote) : ClubCredit {
    class Spend(val maxFeeMsat: Long, val idempotencyKey: String)

    var quotedAmountMsat: Long? = null
    val spends = mutableListOf<Spend>()
    var outcome: ClubCreditOutcome = ClubCreditOutcome.Failed

    override suspend fun quote(invoice: String, amountMsat: Long?): ClubCreditQuote {
        quotedAmountMsat = amountMsat
        return quote
    }

    override suspend fun spend(invoice: String, amountMsat: Long?, maxFeeMsat: Long, idempotencyKey: String): ClubCreditOutcome {
        spends += Spend(maxFeeMsat, idempotencyKey)
        return outcome
    }
}
