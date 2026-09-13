package xyz.lilsus.blip.feature.payment

import fr.acinq.lightning.utils.msat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var confirmation: ExecutablePayment? = null

    suspend fun requestPayment(request: PreparedPayment): ConfirmationResult {
        if (confirmation != null) return ConfirmationResult.Presented
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
        presentation.showLoading(LoadingKind.Resolving)
        val fundingWallet = snapshotFundingWallet()
            ?: return ConfirmationResult.Presented
        currentCoroutineContext().ensureActive()
        val fundingAmountCents =
            if (
                request.amountOverrideMsats != null &&
                fundingWallet.currency == BlinkWalletCurrency.USD
            ) {
                usdPaymentAmountCents(
                    amountMsats = request.amountOverrideMsats,
                    paymentQuote = request.paymentQuote
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
        val requiresConfirmation =
            !request.isManualEntry &&
                (
                    request.source == PaymentRequestSource.DeepLink ||
                        (
                            amountMsats != null &&
                                confirmationPolicy.shouldConfirm(
                                    amountMsats = amountMsats,
                                    isManualEntry = request.isManualEntry,
                                    isPresetTarget =
                                        request.targetContext?.isPreset == true
                                )
                            )
                    )
        currentCoroutineContext().ensureActive()
        val payment =
            ExecutablePayment(
                invoice = request.invoice,
                amountOverrideMsats = request.amountOverrideMsats,
                fundingWallet = fundingWallet,
                fundingAmountCents = fundingAmountCents,
                targetContext = request.targetContext
            )
        if (!requiresConfirmation) {
            return ConfirmationResult.Execute(payment)
        }

        val display =
            confirmationAmount(
                amountMsats = amountMsats ?: 0L,
                paymentQuote = request.paymentQuote
            )
        confirmation = payment
        presentation.showConfirmation(
            amount = display,
            fundingWallet = fundingWallet,
            lnurlPayDisplay = request.lnurlPayDisplay
        )
        return ConfirmationResult.Presented
    }

    fun submit(): ConfirmationResult = takeConfirmation()?.let {
        ConfirmationResult.Execute(it)
    } ?: ConfirmationResult.Presented

    fun dismiss(): Boolean = takeConfirmation() != null

    fun reset() {
        confirmation = null
    }

    private fun takeConfirmation(): ExecutablePayment? = confirmation.also { confirmation = null }

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

    private suspend fun usdPaymentAmountCents(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?
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
        currentCoroutineContext().ensureActive()
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
