package xyz.lilsus.rayl.providers.blink.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.resolve

@Composable
fun blinkErrorMessageFor(error: BlinkUiError): String =
    error.text().resolve { key -> (key as BlinkUiTextKey).androidStringResource() }

@StringRes
private fun BlinkUiTextKey.androidStringResource(): Int = when (this) {
    BlinkUiTextKey.ErrorAuthenticationFailure -> R.string.error_authentication_failure

    BlinkUiTextKey.ErrorBlinkAmountTooSmall -> R.string.error_blink_amount_too_small

    BlinkUiTextKey.ErrorBlinkInsufficientBalance -> R.string.error_blink_insufficient_balance

    BlinkUiTextKey.ErrorBlinkInvalidApiKey -> R.string.error_blink_invalid_api_key

    BlinkUiTextKey.ErrorBlinkInvalidInvoice -> R.string.error_blink_invalid_invoice

    BlinkUiTextKey.ErrorBlinkInvoiceExpired -> R.string.error_blink_invoice_expired

    BlinkUiTextKey.ErrorBlinkLimitExceeded -> R.string.error_blink_limit_exceeded

    BlinkUiTextKey.ErrorBlinkRateLimited -> R.string.error_blink_rate_limited

    BlinkUiTextKey.ErrorBlinkRequiredPermissions -> R.string.error_blink_required_permissions

    BlinkUiTextKey.ErrorBlinkRouteNotFound -> R.string.error_blink_route_not_found

    BlinkUiTextKey.ErrorBlinkSelfPayment -> R.string.error_blink_self_payment

    BlinkUiTextKey.ErrorBlinkWritePermissionDenied ->
        R.string.error_blink_write_permission_denied

    BlinkUiTextKey.ErrorFundingWalletUnavailable -> R.string.error_funding_wallet_unavailable

    BlinkUiTextKey.ErrorMissingWalletConnection -> R.string.error_missing_wallet_connection

    BlinkUiTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

    BlinkUiTextKey.ErrorPaymentRejectedGeneric -> R.string.error_payment_rejected_generic

    BlinkUiTextKey.ErrorPaymentRejectedMessage -> R.string.error_payment_rejected_message

    BlinkUiTextKey.ErrorTimeout -> R.string.error_timeout

    BlinkUiTextKey.ErrorUnexpectedGeneric -> R.string.error_unexpected_generic

    BlinkUiTextKey.ErrorUnexpectedWithDetails -> R.string.error_unexpected_with_details

    BlinkUiTextKey.ErrorWalletAlreadyConnected -> R.string.error_wallet_already_connected
}
