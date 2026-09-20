package xyz.lilsus.rayl.providers.nwc.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentui.exchangeRateUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.invalidInvoicePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.lnurlPaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

internal enum class NwcPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorNetworkUnavailable("error_network_unavailable"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message"),
    ErrorRelayConnectionFailed("error_relay_connection_failed");

    override val table: String = "NwcPayment"
}

internal fun PaymentUiError.toLocalizedText(): LocalizedText = when (this) {
    is PaymentUiError.Nwc -> when (val nwcError = error) {
        NwcPaymentError.MissingWalletConnection ->
            LocalizedText(NwcPaymentTextKey.ErrorMissingWalletConnection)

        NwcPaymentError.NetworkUnavailable ->
            LocalizedText(NwcPaymentTextKey.ErrorNetworkUnavailable)

        is NwcPaymentError.Connection ->
            LocalizedText(NwcPaymentTextKey.ErrorRelayConnectionFailed)

        is NwcPaymentError.Rejected ->
            localizedTextWithOptionalDetail(
                nwcError.detail ?: nwcError.code,
                NwcPaymentTextKey.ErrorPaymentRejectedGeneric,
                NwcPaymentTextKey.ErrorPaymentRejectedMessage
            )

        is NwcPaymentError.OutcomeUnknown ->
            localizedTextWithOptionalDetail(
                nwcError.detail,
                NwcPaymentTextKey.ErrorPaymentUnconfirmed,
                NwcPaymentTextKey.ErrorPaymentUnconfirmedMessage
            )

        is NwcPaymentError.DefinitelyNotSent,
        is NwcPaymentError.Unexpected -> unexpectedPaymentErrorText(nwcError.detail)
    }

    is PaymentUiError.InvalidInvoice -> invalidInvoicePaymentErrorText(reason)

    is PaymentUiError.Lnurl -> lnurlPaymentErrorText(reason)

    is PaymentUiError.ExchangeRateUnavailable ->
        exchangeRateUnavailablePaymentErrorText(currencyCode)

    is PaymentUiError.Unexpected -> unexpectedPaymentErrorText(detail)
}
