package xyz.lilsus.raylsuite.feature.paymentflow

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.utils.Try
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.currentTimestampSeconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.core.widgetspec.WidgetErrorCode
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInvoiceRequest
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInvoiceResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPriceCheck
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPurchase as WidgetOffer
import xyz.lilsus.raylsuite.core.widgetspec.checkWidgetInvoice
import xyz.lilsus.raylsuite.core.widgetspec.newWidgetRequestId
import xyz.lilsus.raylsuite.feature.paymenthub.RaylServices
import xyz.lilsus.raylsuite.feature.paymenthub.host.WidgetCheckoutIntent
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.integration.widget.WidgetClient
import xyz.lilsus.raylsuite.integration.widget.WidgetDefinitionFetch

/** Everything a widget payment carries from checkout to the paid order the Hub records. */
data class WidgetPaymentContext(
    val intent: WidgetCheckoutIntent,
    val definition: WidgetDefinition,
    val itemName: String,
    val advertised: StoredAmount,
    val orderId: String,
    val orderToken: String?,
    val priceCheck: WidgetPriceCheck
)

/**
 * Turns a widget tap into an admitted invoice. Every check that protects the user happens here,
 * before a wallet sees anything: the definition is revalidated, the price the user agreed to is
 * still the advertised one, and the invoice matches it at Rayl's own exchange rate.
 */
internal class WidgetCheckoutPhase(
    private val widgets: WidgetClient,
    private val bitcoinPriceProvider: BitcoinPriceProvider,
    private val presentation: PaymentPresentationPhase,
    private val services: RaylServices
) {
    suspend fun prepare(intent: WidgetCheckoutIntent): AdmissionResult {
        if (presentation.uiState.value != PaymentUiState.Active) return AdmissionResult.Presented
        presentation.showLoading(LoadingKind.Resolving)
        val snapshot = intent.setup.definition
            ?: return stop(WidgetStopReason.Unavailable)
        // A block must reach payments fast: ask for a fresh list, but never wait on it for long.
        services.refreshIfOlderThan(RaylServices.REFRESH_AFTER_MS)
        if (services.blocks(intent.setup.definitionUrl)) return stop(WidgetStopReason.Blocked)
        val definition = when (val fetch = widgets.fetchDefinition(intent.setup.definitionUrl)) {
            is WidgetDefinitionFetch.Loaded -> fetch.definition

            WidgetDefinitionFetch.NotModified -> snapshot

            WidgetDefinitionFetch.Gone,
            is WidgetDefinitionFetch.Invalid -> return stop(WidgetStopReason.Unavailable)

            WidgetDefinitionFetch.NotResponding ->
                return stop(WidgetStopReason.NotResponding, snapshot.host)
        }
        currentCoroutineContext().ensureActive()
        if (definition.id != snapshot.id) return stop(WidgetStopReason.Unavailable)
        if (definition.terms?.version != intent.setup.acceptedTermsVersion) {
            return stop(WidgetStopReason.TermsChanged)
        }
        var productId: String? = null
        var amount: StoredAmount? = null
        val itemName: String
        val advertised: StoredAmount?
        when (val purchase = definition.purchase) {
            is WidgetOffer.Product -> {
                val product = intent.productId?.let(definition::product)
                    ?: return stop(WidgetStopReason.ProductUnavailable)
                if (!product.available) return stop(WidgetStopReason.ProductUnavailable)
                val pinned = intent.setup.pinnedPrices[product.id]
                if (pinned != null && pinned != product.price) {
                    return stop(WidgetStopReason.PriceChanged)
                }
                productId = product.id
                itemName = product.name
                advertised = product.price
            }

            is WidgetOffer.Amount -> {
                amount = intent.amount?.takeIf { it in purchase.range }
                    ?: return stop(WidgetStopReason.ProductUnavailable)
                itemName = definition.title
                advertised = amount
            }

            is WidgetOffer.Lookup -> {
                itemName = definition.title
                advertised = null
            }

            null -> return stop(WidgetStopReason.Unavailable)
        }
        val request = WidgetInvoiceRequest(
            widgetId = definition.id,
            requestId = newWidgetRequestId(),
            productId = productId,
            amount = amount,
            inputs = intent.inputs
        )
        val issued = when (val result = requestInvoice(definition, request)) {
            is WidgetInvoiceResult.Issued -> result

            is WidgetInvoiceResult.NothingDue ->
                return stop(WidgetStopReason.NothingDue, result.message)

            // Nothing was created that could be paid, so every refusal simply stops here.
            is WidgetInvoiceResult.Refused -> return when (result.code) {
                WidgetErrorCode.ProductUnavailable -> stop(WidgetStopReason.ProductUnavailable)

                WidgetErrorCode.InvalidInput,
                WidgetErrorCode.OutOfRange,
                WidgetErrorCode.InvalidRequest -> stop(WidgetStopReason.Refused, result.message)

                WidgetErrorCode.NotFound,
                WidgetErrorCode.RateLimited,
                WidgetErrorCode.Unavailable ->
                    result.message
                        ?.let { stop(WidgetStopReason.Refused, it) }
                        ?: stop(WidgetStopReason.NotResponding, definition.host)
            }

            WidgetInvoiceResult.NotResponding ->
                return stop(WidgetStopReason.NotResponding, definition.host)
        }
        currentCoroutineContext().ensureActive()
        // The provider must charge exactly what the user accepted; lookups state what is due.
        val price = issued.price
        val lookupCurrency = (definition.purchase as? WidgetOffer.Lookup)?.currencyCode
        if (advertised != null && price != advertised) return stop(WidgetStopReason.PriceMismatch)
        if (lookupCurrency != null && price.normalizedCurrencyCode != lookupCurrency) {
            return stop(WidgetStopReason.PriceMismatch)
        }
        val invoice = parseMainnetInvoice(issued.invoice)
            ?: return stop(WidgetStopReason.InvalidInvoice)
        val invoiceMsats = invoice.amount?.msat ?: return stop(WidgetStopReason.InvalidInvoice)
        val rate = rateFor(price)
        currentCoroutineContext().ensureActive()
        val priceCheck = checkWidgetInvoice(price, invoiceMsats, rate)
        if (!priceCheck.payable) {
            return stop(
                if (priceCheck.invoiced == null) {
                    WidgetStopReason.PriceUnverifiable
                } else {
                    WidgetStopReason.PriceMismatch
                }
            )
        }
        val display = LnurlPayDisplay.fromUntrusted(
            domain = definition.host,
            description = issued.description ?: itemName
        )?.copy(recipient = definition.displayName) ?: return stop(WidgetStopReason.InvalidInvoice)
        return AdmissionResult.Payment(
            PreparedPayment(
                invoice = invoice,
                amountOverrideMsats = null,
                source = PaymentRequestSource.Camera,
                lnurlPayDisplay = display,
                widget = WidgetPaymentContext(
                    intent = intent,
                    definition = definition,
                    itemName = issued.description ?: itemName,
                    advertised = price,
                    orderId = issued.orderId,
                    orderToken = issued.orderToken,
                    priceCheck = priceCheck
                )
            )
        )
    }

    /**
     * Sends one purchase attempt. A lost answer is retried with the same request ID; a provider
     * that honours it replays its order, and at most one of the invoices is ever paid.
     */
    private suspend fun requestInvoice(
        definition: WidgetDefinition,
        request: WidgetInvoiceRequest
    ): WidgetInvoiceResult {
        var result = widgets.requestInvoice(definition, request)
        repeat(RETRIES) {
            if (result != WidgetInvoiceResult.NotResponding) return result
            delay(RETRY_DELAY_MS)
            result = widgets.requestInvoice(definition, request)
        }
        return result
    }

    private fun stop(reason: WidgetStopReason, detail: String? = null): AdmissionResult {
        presentation.presentError(PaymentUiError.Widget(reason, detail))
        return AdmissionResult.Presented
    }

    private suspend fun rateFor(amount: StoredAmount): Double? {
        val currency = CurrencyCatalog.infoFor(amount.normalizedCurrencyCode).currency
        return (currency as? DisplayCurrency.Fiat)?.let {
            bitcoinPriceProvider.pricePerBitcoin(it.iso4217)
        }
    }

    private fun parseMainnetInvoice(value: String): Bolt11Invoice? {
        val invoice = (Bolt11Invoice.read(value) as? Try.Success)?.result ?: return null
        if (invoice.chain != Chain.Mainnet) return null
        if (invoice.isExpired(currentTimestampSeconds())) return null
        return invoice
    }

    private companion object {
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 1_500L
    }
}

/** Why a widget checkout stopped. Nothing was paid in any of these cases. */
enum class WidgetStopReason {
    NotResponding,
    Unavailable,
    TermsChanged,
    PriceChanged,
    ProductUnavailable,
    Refused,
    InvalidInvoice,
    PriceMismatch,
    PriceUnverifiable,
    NothingDue,

    /** Rayl blocked this service. */
    Blocked,

    /** The provider could not tell whether it created the order. */
    Uncertain
}
