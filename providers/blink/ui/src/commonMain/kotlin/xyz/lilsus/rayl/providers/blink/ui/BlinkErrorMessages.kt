package xyz.lilsus.rayl.providers.blink.ui

import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkErrorType
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail

/** Semantic text owned by Blink's non-payment UI catalogs. */
internal enum class BlinkUiTextKey(override val key: String) : LocalizedTextKey {
    ErrorAuthenticationFailure("error_authentication_failure"),
    ErrorBlinkAmountTooSmall("error_blink_amount_too_small"),
    ErrorBlinkInsufficientBalance("error_blink_insufficient_balance"),
    ErrorBlinkInvalidApiKey("error_blink_invalid_api_key"),
    ErrorBlinkInvalidInvoice("error_blink_invalid_invoice"),
    ErrorBlinkInvoiceExpired("error_blink_invoice_expired"),
    ErrorBlinkLimitExceeded("error_blink_limit_exceeded"),
    ErrorBlinkRateLimited("error_blink_rate_limited"),
    ErrorBlinkRequiredPermissions("error_blink_required_permissions"),
    ErrorBlinkRouteNotFound("error_blink_route_not_found"),
    ErrorBlinkSelfPayment("error_blink_self_payment"),
    ErrorBlinkWritePermissionDenied("error_blink_write_permission_denied"),
    ErrorFundingWalletUnavailable("error_funding_wallet_unavailable"),
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorNetworkUnavailable("error_network_unavailable"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorTimeout("error_timeout"),
    ErrorUnexpectedGeneric("error_unexpected_generic"),
    ErrorUnexpectedWithDetails("error_unexpected_with_details"),
    ErrorWalletAlreadyConnected("error_wallet_already_connected");

    override val table: String = "BlinkUI"
}

/**
 * Presents failures from Blink features outside the payment flow.
 *
 * Payment owns a separate mapping because timeout and uncertain outcomes need
 * payment-specific wording that must not be reused for ordinary wallet operations.
 */
internal fun BlinkUiError.text(): LocalizedText = when (this) {
    is BlinkUiError.Api -> error.text()
    is BlinkUiError.Connection -> error.text()
    is BlinkUiError.Unexpected -> unexpectedErrorText(detail)
}

private fun BlinkConnectionError.text(): LocalizedText = when (this) {
    BlinkConnectionError.AlreadyConnected ->
        LocalizedText(BlinkUiTextKey.ErrorWalletAlreadyConnected)

    BlinkConnectionError.MissingConnection ->
        LocalizedText(BlinkUiTextKey.ErrorMissingWalletConnection)

    BlinkConnectionError.ApiKeyRequired ->
        LocalizedText(BlinkUiTextKey.ErrorAuthenticationFailure)

    BlinkConnectionError.RequiredPermissionsMissing ->
        LocalizedText(BlinkUiTextKey.ErrorBlinkRequiredPermissions)
}

private fun BlinkApiError.text(): LocalizedText = when (this) {
    is BlinkApiError.BlinkError -> type.text()

    BlinkApiError.FundingWalletUnavailable ->
        LocalizedText(BlinkUiTextKey.ErrorFundingWalletUnavailable)

    BlinkApiError.MissingWalletConnection ->
        LocalizedText(BlinkUiTextKey.ErrorMissingWalletConnection)

    BlinkApiError.NetworkUnavailable -> LocalizedText(BlinkUiTextKey.ErrorNetworkUnavailable)

    is BlinkApiError.PaymentRejected ->
        localizedTextWithOptionalDetail(
            detail = message,
            generic = BlinkUiTextKey.ErrorPaymentRejectedGeneric,
            withDetails = BlinkUiTextKey.ErrorPaymentRejectedMessage
        )

    BlinkApiError.Timeout -> LocalizedText(BlinkUiTextKey.ErrorTimeout)

    is BlinkApiError.Unexpected -> unexpectedErrorText(message)
}

private fun BlinkErrorType.text(): LocalizedText = LocalizedText(
    when (this) {
        BlinkErrorType.PermissionDenied -> BlinkUiTextKey.ErrorBlinkWritePermissionDenied
        BlinkErrorType.InsufficientBalance -> BlinkUiTextKey.ErrorBlinkInsufficientBalance
        BlinkErrorType.RouteNotFound -> BlinkUiTextKey.ErrorBlinkRouteNotFound
        BlinkErrorType.InvoiceExpired -> BlinkUiTextKey.ErrorBlinkInvoiceExpired
        BlinkErrorType.SelfPayment -> BlinkUiTextKey.ErrorBlinkSelfPayment
        BlinkErrorType.InvalidInvoice -> BlinkUiTextKey.ErrorBlinkInvalidInvoice
        BlinkErrorType.AmountTooSmall -> BlinkUiTextKey.ErrorBlinkAmountTooSmall
        BlinkErrorType.LimitExceeded -> BlinkUiTextKey.ErrorBlinkLimitExceeded
        BlinkErrorType.RateLimited -> BlinkUiTextKey.ErrorBlinkRateLimited
        BlinkErrorType.InvalidApiKey -> BlinkUiTextKey.ErrorBlinkInvalidApiKey
    }
)

private fun unexpectedErrorText(detail: String?): LocalizedText = localizedTextWithOptionalDetail(
    detail = detail,
    generic = BlinkUiTextKey.ErrorUnexpectedGeneric,
    withDetails = BlinkUiTextKey.ErrorUnexpectedWithDetails
)

/** Provider failures projected for Blink's reusable non-payment UI. */
sealed interface BlinkUiError {
    data class Api(val error: BlinkApiError) : BlinkUiError

    data class Connection(val error: BlinkConnectionError) : BlinkUiError

    data class Unexpected(val detail: String?) : BlinkUiError
}
