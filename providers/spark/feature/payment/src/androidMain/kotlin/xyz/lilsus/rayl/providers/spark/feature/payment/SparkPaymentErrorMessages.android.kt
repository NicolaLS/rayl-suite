package xyz.lilsus.rayl.providers.spark.feature.payment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource

@Composable
fun sparkPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolve(::sparkPaymentStringResource)

fun getSparkPaymentErrorMessageFor(error: PaymentUiError, context: Context): String =
    error.toLocalizedText().resolve(context, ::sparkPaymentStringResource)

@StringRes
private fun sparkPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is SparkPaymentTextKey -> when (key) {
        SparkPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.spark_error_missing_wallet_connection

        SparkPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.spark_error_payment_rejected_generic

        SparkPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.spark_error_payment_rejected_message

        SparkPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.spark_error_payment_unconfirmed

        SparkPaymentTextKey.ErrorRelayConnectionFailed ->
            R.string.spark_error_relay_connection_failed
    }

    is PaymentUiTextKey -> key.androidStringResource()

    else -> error("Unknown Spark payment text key: ${key.table}.${key.key}")
}
