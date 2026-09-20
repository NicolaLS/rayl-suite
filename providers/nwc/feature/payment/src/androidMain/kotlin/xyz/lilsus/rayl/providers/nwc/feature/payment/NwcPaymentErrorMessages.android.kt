package xyz.lilsus.rayl.providers.nwc.feature.payment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource

@Composable
fun nwcPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolve(::nwcPaymentStringResource)

fun getNwcPaymentErrorMessageFor(error: PaymentUiError, context: Context): String =
    error.toLocalizedText().resolve(context, ::nwcPaymentStringResource)

@StringRes
private fun nwcPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is NwcPaymentTextKey -> when (key) {
        NwcPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.error_missing_wallet_connection

        NwcPaymentTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

        NwcPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.error_payment_rejected_generic

        NwcPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.error_payment_rejected_message

        NwcPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        NwcPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message

        NwcPaymentTextKey.ErrorRelayConnectionFailed ->
            R.string.error_relay_connection_failed
    }

    is PaymentUiTextKey -> key.androidStringResource()

    else -> error("Unknown Nwc payment text key: ${key.table}.${key.key}")
}
