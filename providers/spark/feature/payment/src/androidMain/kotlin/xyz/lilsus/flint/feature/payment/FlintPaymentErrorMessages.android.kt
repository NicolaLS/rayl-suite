package xyz.lilsus.flint.feature.payment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource

@Composable
fun flintPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolve(::flintPaymentStringResource)

fun getFlintPaymentErrorMessageFor(error: PaymentUiError, context: Context): String =
    error.toLocalizedText().resolve(context, ::flintPaymentStringResource)

@StringRes
private fun flintPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is FlintPaymentTextKey -> when (key) {
        FlintPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.spark_error_missing_wallet_connection

        FlintPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.spark_error_payment_rejected_generic

        FlintPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.spark_error_payment_rejected_message

        FlintPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.spark_error_payment_unconfirmed

        FlintPaymentTextKey.ErrorRelayConnectionFailed ->
            R.string.spark_error_relay_connection_failed
    }

    is PaymentUiTextKey -> key.androidStringResource()

    else -> error("Unknown Flint payment text key: ${key.table}.${key.key}")
}
