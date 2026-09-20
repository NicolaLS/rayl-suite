package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentui.exchangeRateUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.invalidInvoicePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.lnurlPaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

internal enum class SparkPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorMissingWalletConnection("spark_error_missing_wallet_connection"),
    ErrorPaymentRejectedGeneric("spark_error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("spark_error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("spark_error_payment_unconfirmed"),
    ErrorRelayConnectionFailed("spark_error_relay_connection_failed");

    override val table: String = "SparkPayment"
}

internal fun PaymentUiError.toLocalizedText(): LocalizedText = when (this) {
    is PaymentUiError.Spark -> when (val sparkError = error) {
        SparkPaymentError.WalletUnavailable ->
            LocalizedText(SparkPaymentTextKey.ErrorMissingWalletConnection)

        SparkPaymentError.SdkUnavailable ->
            LocalizedText(SparkPaymentTextKey.ErrorRelayConnectionFailed)

        is SparkPaymentError.Rejected ->
            localizedTextWithOptionalDetail(
                sparkError.detail,
                SparkPaymentTextKey.ErrorPaymentRejectedGeneric,
                SparkPaymentTextKey.ErrorPaymentRejectedMessage
            )

        SparkPaymentError.OutcomeUnknown ->
            LocalizedText(SparkPaymentTextKey.ErrorPaymentUnconfirmed)
    }

    is PaymentUiError.InvalidInvoice -> invalidInvoicePaymentErrorText(reason)

    is PaymentUiError.Lnurl -> lnurlPaymentErrorText(reason)

    is PaymentUiError.ExchangeRateUnavailable ->
        exchangeRateUnavailablePaymentErrorText(currencyCode)

    is PaymentUiError.Unexpected -> unexpectedPaymentErrorText(detail)
}
