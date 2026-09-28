package xyz.lilsus.raylsuite.feature.paymentflow

import xyz.lilsus.raylsuite.core.payment.LnurlError
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceResolutionError
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.feature.paymentcurrency.CurrencyManagerError
import xyz.lilsus.raylsuite.feature.paymentui.exchangeRateUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.invalidInvoicePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.lnurlPaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.networkUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

fun PaymentUiError.toLocalizedText(): LocalizedText = when (this) {
    is PaymentUiError.Provider -> error.toLocalizedText()

    is PaymentUiError.InvalidInvoice -> invalidInvoicePaymentErrorText(reason)

    is PaymentUiError.Lnurl -> lnurlPaymentErrorText(reason)

    PaymentUiError.NetworkUnavailable -> networkUnavailablePaymentErrorText()

    is PaymentUiError.ExchangeRateUnavailable ->
        exchangeRateUnavailablePaymentErrorText(currencyCode)

    is PaymentUiError.Unexpected -> unexpectedPaymentErrorText(detail)
}

internal fun CurrencyManagerError.toPaymentUiError(): PaymentUiError = when (this) {
    is CurrencyManagerError.ExchangeRateUnavailable ->
        PaymentUiError.ExchangeRateUnavailable(currencyCode)
}

internal fun LnurlError.toPaymentUiError(): PaymentUiError = when (this) {
    LnurlError.NetworkUnavailable -> PaymentUiError.NetworkUnavailable
    is LnurlError.Protocol -> PaymentUiError.Lnurl(reason)
    is LnurlError.Unexpected -> PaymentUiError.Lnurl(detail)
}

internal fun LnurlInvoiceResolutionError.toPaymentUiError(): PaymentUiError = when (this) {
    is LnurlInvoiceResolutionError.Client -> error.toPaymentUiError()

    LnurlInvoiceResolutionError.CommentRejected ->
        PaymentUiError.InvalidInvoice("Description is too long for this address")

    LnurlInvoiceResolutionError.MalformedInvoice ->
        PaymentUiError.InvalidInvoice("Failed to parse BOLT11 invoice")

    LnurlInvoiceResolutionError.ExpiredInvoice ->
        PaymentUiError.InvalidInvoice("Invoice has expired")

    LnurlInvoiceResolutionError.AmountOutOfRange ->
        PaymentUiError.InvalidInvoice("Amount is outside the allowed range")

    is LnurlInvoiceResolutionError.AmountMismatch ->
        PaymentUiError.InvalidInvoice("LNURL invoice amount does not match")

    LnurlInvoiceResolutionError.MetadataMismatch ->
        PaymentUiError.InvalidInvoice("LNURL invoice metadata mismatch")
}
