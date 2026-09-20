package xyz.lilsus.rayl.providers.blink.feature.payment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource

@Composable
fun blinkPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolve(::blinkPaymentStringResource)

fun getBlinkPaymentErrorMessageFor(error: PaymentUiError, context: Context): String =
    error.toLocalizedText().resolve(context, ::blinkPaymentStringResource)

@StringRes
private fun blinkPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is BlinkPaymentTextKey -> when (key) {
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

    is PaymentUiTextKey -> key.androidStringResource()

    else -> error("Unknown Blink payment text key: ${key.table}.${key.key}")
}
