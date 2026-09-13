package xyz.lilsus.blip.feature.payment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource

@Composable
fun blipPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolve(::blipPaymentStringResource)

fun getBlipPaymentErrorMessageFor(error: PaymentUiError, context: Context): String =
    error.toLocalizedText().resolve(context, ::blipPaymentStringResource)

@StringRes
private fun blipPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is BlipPaymentTextKey -> when (key) {
        BlipPaymentTextKey.ErrorBlinkAmountTooSmall -> R.string.error_blink_amount_too_small

        BlipPaymentTextKey.ErrorBlinkInsufficientBalance ->
            R.string.error_blink_insufficient_balance

        BlipPaymentTextKey.ErrorBlinkInvalidApiKey -> R.string.error_blink_invalid_api_key

        BlipPaymentTextKey.ErrorBlinkInvalidInvoice -> R.string.error_blink_invalid_invoice

        BlipPaymentTextKey.ErrorBlinkInvoiceExpired -> R.string.error_blink_invoice_expired

        BlipPaymentTextKey.ErrorBlinkLimitExceeded -> R.string.error_blink_limit_exceeded

        BlipPaymentTextKey.ErrorBlinkPermissionDenied -> R.string.error_blink_permission_denied

        BlipPaymentTextKey.ErrorBlinkRateLimited -> R.string.error_blink_rate_limited

        BlipPaymentTextKey.ErrorBlinkRouteNotFound -> R.string.error_blink_route_not_found

        BlipPaymentTextKey.ErrorBlinkSelfPayment -> R.string.error_blink_self_payment

        BlipPaymentTextKey.ErrorFundingWalletUnavailable ->
            R.string.error_funding_wallet_unavailable

        BlipPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.error_missing_wallet_connection

        BlipPaymentTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

        BlipPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.error_payment_rejected_generic

        BlipPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.error_payment_rejected_message

        BlipPaymentTextKey.ErrorPaymentStatusUnknown -> R.string.error_payment_status_unknown

        BlipPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        BlipPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message
    }

    is PaymentUiTextKey -> key.androidStringResource()

    else -> error("Unknown Blip payment text key: ${key.table}.${key.key}")
}
