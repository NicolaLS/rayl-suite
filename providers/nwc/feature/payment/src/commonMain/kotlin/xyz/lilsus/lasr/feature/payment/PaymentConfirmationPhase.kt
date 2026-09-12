package xyz.lilsus.lasr.feature.payment

import fr.acinq.lightning.utils.msat
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentConfirmationPolicy
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount

internal class PaymentConfirmationPhase(
    private val currencyManager: PaymentCurrencyManager,
    private val confirmationPolicy: PaymentConfirmationPolicy,
    private val presentation: PaymentPresentationPhase
) {
    private val confirmationSession = PaymentConfirmationSession()

    val isIdle: Boolean
        get() = confirmationSession.state == PaymentConfirmationState.Idle

    suspend fun requestPayment(
        request: PreparedPayment,
        token: PaymentTaskToken
    ): ConfirmationResult {
        if (!confirmationSession.begin(token)) return ConfirmationResult.Presented
        try {
            val amountMsats = request.amountOverrideMsats ?: request.invoice.amount?.msat
            if (
                request.paymentQuote != null &&
                request.paymentQuote.amountMsats != amountMsats
            ) {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("Quoted amount does not match invoice")
                )
                return ConfirmationResult.Presented
            }
            val isManualEntry =
                request.origin == PendingOrigin.ManualEntry ||
                    request.origin == PendingOrigin.LnurlManual
            val requiresConfirmation =
                !isManualEntry &&
                    (
                        request.source == PaymentRequestSource.DeepLink ||
                            (
                                amountMsats != null &&
                                    confirmationPolicy.shouldConfirm(
                                        amountMsats = amountMsats,
                                        isManualEntry = isManualEntry,
                                        isPresetTarget = request.targetContext?.isPreset == true
                                    )
                                )
                        )
            token.ensureCurrent()
            val payment =
                ExecutablePayment(
                    invoice = request.invoice,
                    amountOverrideMsats = request.amountOverrideMsats,
                    origin = request.origin,
                    dynamicSourceKey = request.dynamicSourceKey,
                    targetContext = request.targetContext,
                    replacesDynamicGuardId = request.replacesDynamicGuardId
                )
            if (!requiresConfirmation) {
                return ConfirmationResult.Execute(payment)
            }

            val display =
                confirmationAmount(
                    amountMsats = amountMsats ?: 0L,
                    paymentQuote = request.paymentQuote,
                    token = token
                )
            if (
                !confirmationSession.await(
                    token,
                    payment
                )
            ) {
                return ConfirmationResult.Presented
            }
            presentation.showConfirmation(display, lnurlPayDisplay = request.lnurlPayDisplay)
            return ConfirmationResult.Presented
        } finally {
            confirmationSession.finishPreparation(token)
        }
    }

    fun submit(): ConfirmationResult = confirmationSession.take()?.let {
        ConfirmationResult.Execute(it)
    } ?: ConfirmationResult.Presented

    fun dismiss(): ConfirmationDismissal = when (confirmationSession.take()?.origin) {
        PendingOrigin.Invoice,
        PendingOrigin.LnurlFixed -> ConfirmationDismissal.Active

        PendingOrigin.ManualEntry,
        PendingOrigin.LnurlManual -> ConfirmationDismissal.ManualAmount

        null -> ConfirmationDismissal.None
    }

    fun reset() {
        confirmationSession.reset()
    }

    private suspend fun confirmationAmount(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?,
        token: PaymentTaskToken
    ): PaymentConfirmationAmount {
        val exactSats =
            DisplayAmount(amountMsats / MSATS_PER_SAT, DisplayCurrency.Satoshi)
        paymentQuote?.let { quote ->
            return PaymentConfirmationAmount(
                primary = quote.requestedAmount,
                exactSats =
                    exactSats.takeIf {
                        quote.requestedAmount.currency is DisplayCurrency.Fiat
                    }
            )
        }
        val preferredAmount = currencyManager.convertMsatsToFreshDisplay(amountMsats)
        token.ensureCurrent()
        return PaymentConfirmationAmount(
            primary = preferredAmount,
            exactSats = exactSats.takeIf { preferredAmount.currency is DisplayCurrency.Fiat },
            primaryIsEstimate = preferredAmount.currency is DisplayCurrency.Fiat
        )
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
