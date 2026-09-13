package xyz.lilsus.blip.feature.payment

import xyz.lilsus.blip.integration.blink.BlinkApiError
import xyz.lilsus.blip.integration.blink.BlinkErrorType
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentui.exchangeRateUnavailablePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.invalidInvoicePaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.lnurlPaymentErrorText
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

internal enum class BlipPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorBlinkAmountTooSmall("error_blink_amount_too_small"),
    ErrorBlinkInsufficientBalance("error_blink_insufficient_balance"),
    ErrorBlinkInvalidApiKey("error_blink_invalid_api_key"),
    ErrorBlinkInvalidInvoice("error_blink_invalid_invoice"),
    ErrorBlinkInvoiceExpired("error_blink_invoice_expired"),
    ErrorBlinkLimitExceeded("error_blink_limit_exceeded"),
    ErrorBlinkPermissionDenied("error_blink_permission_denied"),
    ErrorBlinkRateLimited("error_blink_rate_limited"),
    ErrorBlinkRouteNotFound("error_blink_route_not_found"),
    ErrorBlinkSelfPayment("error_blink_self_payment"),
    ErrorFundingWalletUnavailable("error_funding_wallet_unavailable"),
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorNetworkUnavailable("error_network_unavailable"),
    ErrorPaymentStatusUnknown("error_payment_status_unknown"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message");

    override val table: String = "BlipPayment"
}

internal fun PaymentUiError.toLocalizedText(): LocalizedText = when (this) {
    PaymentUiError.StatusUnknown -> LocalizedText(BlipPaymentTextKey.ErrorPaymentStatusUnknown)

    is PaymentUiError.Blink -> error.toLocalizedText()

    is PaymentUiError.InvalidInvoice -> invalidInvoicePaymentErrorText(reason)

    is PaymentUiError.Lnurl -> lnurlPaymentErrorText(reason)

    is PaymentUiError.ExchangeRateUnavailable ->
        exchangeRateUnavailablePaymentErrorText(currencyCode)

    is PaymentUiError.Unexpected -> unexpectedPaymentErrorText(detail)
}

private fun BlinkApiError.toLocalizedText(): LocalizedText = when (this) {
    BlinkApiError.FundingWalletUnavailable ->
        LocalizedText(BlipPaymentTextKey.ErrorFundingWalletUnavailable)

    BlinkApiError.MissingWalletConnection ->
        LocalizedText(BlipPaymentTextKey.ErrorMissingWalletConnection)

    BlinkApiError.NetworkUnavailable ->
        LocalizedText(BlipPaymentTextKey.ErrorNetworkUnavailable)

    BlinkApiError.Timeout ->
        LocalizedText(BlipPaymentTextKey.ErrorPaymentUnconfirmed)

    is BlinkApiError.PaymentRejected ->
        code?.toBlinkErrorType()?.toLocalizedText()
            ?: localizedTextWithOptionalDetail(
                detail = message,
                generic = BlipPaymentTextKey.ErrorPaymentRejectedGeneric,
                withDetails = BlipPaymentTextKey.ErrorPaymentRejectedMessage
            )

    is BlinkApiError.Unexpected ->
        localizedTextWithOptionalDetail(
            detail = message,
            generic = BlipPaymentTextKey.ErrorPaymentUnconfirmed,
            withDetails = BlipPaymentTextKey.ErrorPaymentUnconfirmedMessage
        )

    is BlinkApiError.BlinkError -> type.toLocalizedText()
}

private fun BlinkErrorType.toLocalizedText(): LocalizedText = LocalizedText(
    when (this) {
        BlinkErrorType.PermissionDenied -> BlipPaymentTextKey.ErrorBlinkPermissionDenied
        BlinkErrorType.InsufficientBalance -> BlipPaymentTextKey.ErrorBlinkInsufficientBalance
        BlinkErrorType.RouteNotFound -> BlipPaymentTextKey.ErrorBlinkRouteNotFound
        BlinkErrorType.InvoiceExpired -> BlipPaymentTextKey.ErrorBlinkInvoiceExpired
        BlinkErrorType.SelfPayment -> BlipPaymentTextKey.ErrorBlinkSelfPayment
        BlinkErrorType.InvalidInvoice -> BlipPaymentTextKey.ErrorBlinkInvalidInvoice
        BlinkErrorType.AmountTooSmall -> BlipPaymentTextKey.ErrorBlinkAmountTooSmall
        BlinkErrorType.LimitExceeded -> BlipPaymentTextKey.ErrorBlinkLimitExceeded
        BlinkErrorType.RateLimited -> BlipPaymentTextKey.ErrorBlinkRateLimited
        BlinkErrorType.InvalidApiKey -> BlipPaymentTextKey.ErrorBlinkInvalidApiKey
    }
)

private fun String.toBlinkErrorType(): BlinkErrorType? =
    BlinkErrorType.entries.firstOrNull { it.name == this }
