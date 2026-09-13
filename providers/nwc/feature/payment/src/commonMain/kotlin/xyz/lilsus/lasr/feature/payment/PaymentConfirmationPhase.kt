package xyz.lilsus.lasr.feature.payment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var awaitingConfirmation: ExecutablePayment? = null

    suspend fun requestPayment(request: PreparedPayment): ConfirmationResult {
        val amountMsats = request.amountOverrideMsats ?: request.invoice.amount?.msat
        if (request.paymentQuote != null && request.paymentQuote.amountMsats != amountMsats) {
            presentation.presentError(
                PaymentUiError.InvalidInvoice("Quoted amount does not match invoice")
            )
            return ConfirmationResult.Presented
        }
        val requiresConfirmation = !request.isManualEntry && (
            request.source == PaymentRequestSource.DeepLink || (
                amountMsats != null && confirmationPolicy.shouldConfirm(
                    amountMsats = amountMsats,
                    isManualEntry = false,
                    isPresetTarget = request.targetContext?.isPreset == true
                )
                )
            )
        currentCoroutineContext().ensureActive()
        val payment = ExecutablePayment(
            invoice = request.invoice,
            amountOverrideMsats = request.amountOverrideMsats,
            targetContext = request.targetContext
        )
        if (!requiresConfirmation) return ConfirmationResult.Execute(payment)

        val display = confirmationAmount(amountMsats ?: 0L, request.paymentQuote)
        currentCoroutineContext().ensureActive()
        awaitingConfirmation = payment
        presentation.showConfirmation(display, lnurlPayDisplay = request.lnurlPayDisplay)
        return ConfirmationResult.Presented
    }

    fun submit(): ConfirmationResult {
        val payment = awaitingConfirmation ?: return ConfirmationResult.Presented
        awaitingConfirmation = null
        return ConfirmationResult.Execute(payment)
    }

    fun dismiss(): Boolean {
        if (awaitingConfirmation == null) return false
        awaitingConfirmation = null
        return true
    }

    fun reset() {
        awaitingConfirmation = null
    }

    private suspend fun confirmationAmount(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?
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
        currentCoroutineContext().ensureActive()
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
