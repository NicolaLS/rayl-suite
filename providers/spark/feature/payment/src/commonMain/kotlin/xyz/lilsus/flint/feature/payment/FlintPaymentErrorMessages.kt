package xyz.lilsus.flint.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentui.exchangeRateUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.invalidInvoicePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.lnurlPaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

internal enum class FlintPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorRelayConnectionFailed("error_relay_connection_failed");

    override val table: String = "FlintPayment"
}

internal fun PaymentUiError.toLocalizedText(): LocalizedText = when (this) {
    is PaymentUiError.Spark -> when (val sparkError = error) {
        SparkPaymentError.WalletUnavailable ->
            LocalizedText(FlintPaymentTextKey.ErrorMissingWalletConnection)

        SparkPaymentError.SdkUnavailable ->
            LocalizedText(FlintPaymentTextKey.ErrorRelayConnectionFailed)

        is SparkPaymentError.Rejected ->
            localizedTextWithOptionalDetail(
                sparkError.detail,
                FlintPaymentTextKey.ErrorPaymentRejectedGeneric,
                FlintPaymentTextKey.ErrorPaymentRejectedMessage
            )

        SparkPaymentError.OutcomeUnknown ->
            LocalizedText(FlintPaymentTextKey.ErrorPaymentUnconfirmed)
    }

    is PaymentUiError.InvalidInvoice -> invalidInvoicePaymentErrorText(reason)

    is PaymentUiError.Lnurl -> lnurlPaymentErrorText(reason)

    is PaymentUiError.ExchangeRateUnavailable ->
        exchangeRateUnavailablePaymentErrorText(currencyCode)

    is PaymentUiError.Unexpected -> unexpectedPaymentErrorText(detail)
}
