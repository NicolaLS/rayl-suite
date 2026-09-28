package xyz.lilsus.rayl.providers.blink.feature.payment

import androidx.annotation.StringRes
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey

/** Android resources for Blink's own payment text; shared payment text resolves itself. */
@StringRes
fun blinkPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is BlinkPaymentTextKey -> when (key) {
        BlinkPaymentTextKey.ConfirmPaymentFundingBitcoin ->
            R.string.confirm_payment_funding_bitcoin

        BlinkPaymentTextKey.ConfirmPaymentFundingStablesats ->
            R.string.confirm_payment_funding_stablesats

        BlinkPaymentTextKey.ErrorBlinkAmountTooSmall -> R.string.error_blink_amount_too_small

        BlinkPaymentTextKey.ErrorBlinkInsufficientBalance ->
            R.string.error_blink_insufficient_balance

        BlinkPaymentTextKey.ErrorBlinkInvalidApiKey -> R.string.error_blink_invalid_api_key

        BlinkPaymentTextKey.ErrorBlinkInvalidInvoice -> R.string.error_blink_invalid_invoice

        BlinkPaymentTextKey.ErrorBlinkInvoiceExpired -> R.string.error_blink_invoice_expired

        BlinkPaymentTextKey.ErrorBlinkLimitExceeded -> R.string.error_blink_limit_exceeded

        BlinkPaymentTextKey.ErrorBlinkPermissionDenied -> R.string.error_blink_permission_denied

        BlinkPaymentTextKey.ErrorBlinkRateLimited -> R.string.error_blink_rate_limited

        BlinkPaymentTextKey.ErrorBlinkRouteNotFound -> R.string.error_blink_route_not_found

        BlinkPaymentTextKey.ErrorBlinkSelfPayment -> R.string.error_blink_self_payment

        BlinkPaymentTextKey.ErrorFundingWalletUnavailable ->
            R.string.error_funding_wallet_unavailable

        BlinkPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.error_missing_wallet_connection

        BlinkPaymentTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

        BlinkPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.error_payment_rejected_generic

        BlinkPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.error_payment_rejected_message

        BlinkPaymentTextKey.ErrorPaymentStatusUnknown -> R.string.error_payment_status_unknown

        BlinkPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        BlinkPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message
    }

    else -> error("Unknown Blink payment text key: ${key.table}.${key.key}")
}
