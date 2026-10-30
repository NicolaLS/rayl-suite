package xyz.lilsus.rayl.providers.rayl.feature.payment

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCredit
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCreditOutcome
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubCreditQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayOutcome
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayments
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuoteResult
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketTimings
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentSettlement
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettleablePayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

/** An admitted invoice that Club credit or the pocket has funded and fee-quoted. */
class RaylPayment internal constructor(
    val request: PreparedPayment,
    internal val funding: RaylPaymentFunding
) : SettleablePayment by request

internal sealed interface RaylPaymentFunding {
    class Pocket(val quote: PocketQuote) : RaylPaymentFunding

    /** One [idempotencyKey] per prepared payment, so confirming it again never pays twice. */
    class Credit(val feeMsat: Long, val idempotencyKey: String = Uuid.random().toString()) :
        RaylPaymentFunding
}

/**
 * Pays admitted invoices with Club credit when it can and from the Rayl pocket otherwise (D-19):
 * the member never chooses, and the confirmation shows only the amount.
 *
 * Waiting for the pocket to connect happens in [prepare], before anything is submitted, so its
 * failures mean "not paid" and are safe to retry. Once [settle] submits, an unclear result is
 * [SettlementOutcome.Unknown] and must never be retried or shown as failed: it may have paid.
 */
class RaylPaymentSettlement(
    private val pocket: PocketPayments,
    private val credit: ClubCredit? = null
) : PaymentSettlement<RaylPayment> {
    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<RaylPayment> {
        PocketTimings.mark("payment admitted (${request.source})")
        val overrideMsats = request.amountOverrideMsats
        if (overrideMsats != null && overrideMsats % MSATS_PER_SAT != 0L) {
            return unavailable(RaylPaymentError.SubSatoshiAmount)
        }
        val invoice = request.invoice.write()
        when (val quote = credit?.quote(invoice, overrideMsats)) {
            is ClubCreditQuote.Quoted -> return SettlementPreparation.Ready(
                RaylPayment(request, RaylPaymentFunding.Credit(quote.feeMsat))
            )

            is ClubCreditQuote.InvoiceRejected -> return SettlementPreparation.Unavailable(
                PaymentUiError.InvalidInvoice("Invoice has expired".takeIf { quote.expired })
            )

            ClubCreditQuote.NotUsable, null -> Unit
        }
        return when (
            val result = pocket.quote(invoice, overrideMsats?.div(MSATS_PER_SAT))
        ) {
            is PocketQuoteResult.Quoted ->
                SettlementPreparation.Ready(
                    RaylPayment(request, RaylPaymentFunding.Pocket(result.quote))
                )

            PocketQuoteResult.InsufficientFunds -> unavailable(RaylPaymentError.InsufficientFunds)

            PocketQuoteResult.NotReady -> unavailable(RaylPaymentError.PocketNotReady)

            is PocketQuoteResult.Rejected -> unavailable(RaylPaymentError.Rejected(result.detail))
        }
    }

    override suspend fun settle(payment: RaylPayment): SettlementOutcome {
        PocketTimings.mark("payment confirmed")
        val quote = when (val funding = payment.funding) {
            is RaylPaymentFunding.Credit -> return settleWithCredit(payment, funding)
            is RaylPaymentFunding.Pocket -> funding.quote
        }
        val outcome = try {
            pocket.pay(quote)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            PocketPayOutcome.Unknown(cause.message)
        }
        return when (outcome) {
            is PocketPayOutcome.Paid ->
                SettlementOutcome.Paid(
                    feeMsats = outcome.feeSats * MSATS_PER_SAT,
                    preimage = outcome.preimage
                )

            is PocketPayOutcome.Failed ->
                SettlementOutcome.Failed(
                    PaymentUiError.Provider(RaylPaymentError.Rejected(outcome.detail))
                )

            is PocketPayOutcome.Unknown ->
                SettlementOutcome.Unknown(
                    PaymentUiError.Provider(RaylPaymentError.OutcomeUnknown(outcome.detail))
                )
        }
    }

    private suspend fun settleWithCredit(
        payment: RaylPayment,
        funding: RaylPaymentFunding.Credit
    ): SettlementOutcome {
        val outcome = checkNotNull(credit).spend(
            invoice = payment.request.invoice.write(),
            amountMsat = payment.request.amountOverrideMsats,
            maxFeeMsat = funding.feeMsat,
            idempotencyKey = funding.idempotencyKey
        )
        return when (outcome) {
            is ClubCreditOutcome.Paid ->
                SettlementOutcome.Paid(feeMsats = outcome.feeMsat, preimage = outcome.preimage)

            ClubCreditOutcome.Failed ->
                SettlementOutcome.Failed(PaymentUiError.Provider(RaylPaymentError.Rejected(null)))

            ClubCreditOutcome.Unknown -> SettlementOutcome.Unknown(
                PaymentUiError.Provider(RaylPaymentError.OutcomeUnknown(null))
            )
        }
    }

    private fun unavailable(error: RaylPaymentError) =
        SettlementPreparation.Unavailable(PaymentUiError.Provider(error))

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
