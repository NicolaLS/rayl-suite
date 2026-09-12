package xyz.lilsus.blip.feature.payment

import fr.acinq.lightning.utils.msat
import kotlinx.coroutines.CancellationException
import xyz.lilsus.blip.integration.blink.BlinkApiError
import xyz.lilsus.blip.integration.blink.BlinkApiException
import xyz.lilsus.blip.integration.blink.BlinkConnectionException
import xyz.lilsus.blip.integration.blink.BlinkFundingWallet
import xyz.lilsus.blip.integration.blink.BlinkWallet
import xyz.lilsus.blip.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.payment.roundToFullSatoshis
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentConfirmationPolicy
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount

internal class PaymentConfirmationPhase(
    private val blinkWallet: BlinkWallet,
    private val bitcoinPriceProvider: BitcoinPriceProvider,
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
            val fundingWallet =
                request.fundingWalletSnapshot ?: snapshotFundingWallet()
                    ?: return ConfirmationResult.Presented
            token.ensureCurrent()
            val fundingAmountCents =
                if (
                    request.amountOverrideMsats != null &&
                    fundingWallet.currency == BlinkWalletCurrency.USD
                ) {
                    usdPaymentAmountCents(
                        amountMsats = request.amountOverrideMsats,
                        paymentQuote = request.paymentQuote,
                        token = token
                    )
                        ?: run {
                            presentation.presentError(
                                PaymentUiError.ExchangeRateUnavailable(USD_CODE)
                            )
                            return ConfirmationResult.Presented
                        }
                } else {
                    null
                }
            val isManualEntry =
                request.origin == PendingOrigin.ManualEntry ||
                    request.origin == PendingOrigin.LnurlManual
            val requiresConfirmation =
                !isManualEntry && !request.lnurlAuthorized &&
                    (
                        request.source == PaymentRequestSource.DeepLink ||
                            (
                                amountMsats != null &&
                                    confirmationPolicy.shouldConfirm(
                                        amountMsats = amountMsats,
                                        isManualEntry = isManualEntry,
                                        isPresetTarget =
                                            request.targetContext?.isPreset == true
                                    )
                                )
                        )
            token.ensureCurrent()
            val payment =
                ExecutablePayment(
                    invoice = request.invoice,
                    amountOverrideMsats = request.amountOverrideMsats,
                    fundingWallet = fundingWallet,
                    fundingAmountCents = fundingAmountCents,
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
                    PendingConfirmation.Payment(payment)
                )
            ) {
                return ConfirmationResult.Presented
            }
            presentation.showConfirmation(
                amount = display,
                fundingWallet = fundingWallet,
                lnurlPayDisplay = request.lnurlPayDisplay
            )
            return ConfirmationResult.Presented
        } finally {
            confirmationSession.finishPreparation(token)
        }
    }

    suspend fun reviewLnurlPayment(
        review: LnurlReviewRequest,
        token: PaymentTaskToken
    ): ConfirmationResult {
        if (!confirmationSession.begin(token)) return ConfirmationResult.Presented
        try {
            val fundingWallet = snapshotFundingWallet()
                ?: return ConfirmationResult.Presented
            token.ensureCurrent()
            val roundedAmount = roundToFullSatoshis(review.amountMsats)
            if (
                roundedAmount == null ||
                review.amountMsats !in
                review.session.params.minSendable..review.session.params.maxSendable ||
                roundedAmount !in
                review.session.params.minSendable..review.session.params.maxSendable
            ) {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("Amount is outside the allowed range")
                )
                return ConfirmationResult.Presented
            }
            val confirmationAmount =
                confirmationAmount(
                    amountMsats = roundedAmount,
                    paymentQuote = review.paymentQuote,
                    token = token
                )
            val approval =
                ApprovedLnurlReview(
                    request = review.copy(amountMsats = roundedAmount),
                    fundingWallet = fundingWallet
                )
            if (
                !confirmationSession.await(
                    token,
                    PendingConfirmation.Lnurl(approval)
                )
            ) {
                return ConfirmationResult.Presented
            }
            presentation.showConfirmation(
                amount = confirmationAmount,
                fundingWallet = fundingWallet,
                lnurlPayDisplay = review.session.display
            )
            return ConfirmationResult.Presented
        } finally {
            confirmationSession.finishPreparation(token)
        }
    }

    fun submit(): ConfirmationResult = when (val confirmation = confirmationSession.take()) {
        is PendingConfirmation.Lnurl ->
            ConfirmationResult.ResolveLnurl(confirmation.approval)

        is PendingConfirmation.Payment ->
            ConfirmationResult.Execute(confirmation.payment)

        null -> ConfirmationResult.Presented
    }

    fun dismiss(): ConfirmationDismissal = when (val confirmation = confirmationSession.take()) {
        is PendingConfirmation.Lnurl ->
            if (confirmation.approval.request.isManualEntry) {
                ConfirmationDismissal.LnurlManualAmount
            } else {
                ConfirmationDismissal.Active
            }

        is PendingConfirmation.Payment ->
            when (confirmation.payment.origin) {
                PendingOrigin.Invoice,
                PendingOrigin.LnurlFixed -> ConfirmationDismissal.Active

                PendingOrigin.ManualEntry,
                PendingOrigin.LnurlManual ->
                    ConfirmationDismissal.ManualAmount
            }

        null -> ConfirmationDismissal.None
    }

    fun reset() {
        confirmationSession.reset()
    }

    private fun snapshotFundingWallet(): BlinkFundingWallet? = try {
        blinkWallet.prepareFundingWallet()
    } catch (cause: CancellationException) {
        throw cause
    } catch (error: BlinkApiException) {
        presentation.presentError(PaymentUiError.Blink(error.error))
        null
    } catch (_: BlinkConnectionException) {
        presentation.presentError(
            PaymentUiError.Blink(BlinkApiError.MissingWalletConnection)
        )
        null
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

    private suspend fun usdPaymentAmountCents(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?,
        token: PaymentTaskToken
    ): Long? {
        val requestedAmount = paymentQuote?.requestedAmount
        val requestedCurrency = requestedAmount?.currency as? DisplayCurrency.Fiat
        if (
            requestedAmount != null &&
            requestedCurrency?.iso4217?.equals(USD_CODE, ignoreCase = true) == true
        ) {
            return requestedAmount.minor.takeIf { it > 0L }
        }

        val usdRate = bitcoinPriceProvider.pricePerBitcoin(USD_CODE)
        token.ensureCurrent()
        val validRate = usdRate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return convertMsatsToDisplayAmount(
            msats = amountMsats,
            info = CurrencyCatalog.infoFor(USD_CODE),
            fiatPricePerBitcoin = validRate
        )?.minor?.takeIf { it > 0L }
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
        const val USD_CODE = "USD"
    }
}
