package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState

/** Spark-owned projection into the provider-neutral native Scan presentation state. */
suspend fun PaymentUiState.toNativePaymentScreenState(): PaymentScreenState = when (this) {
    PaymentUiState.Active -> PaymentScreenState.Active

    is PaymentUiState.Loading ->
        PaymentScreenState.Loading(
            if (kind == LoadingKind.Resolving) {
                PaymentLoadingKind.Resolving
            } else {
                PaymentLoadingKind.Paying
            }
        )

    is PaymentUiState.EnterAmount -> PaymentScreenState.EnterAmount(entry, lnurlPayDisplay)

    is PaymentUiState.Confirm -> PaymentScreenState.Confirm(amount, lnurlPayDisplay)

    is PaymentUiState.Success ->
        PaymentScreenState.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(getSparkPaymentErrorMessageFor(error))
}

suspend fun SessionTransactionItem.toNativeRecentItem(
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentRecentItem = NativePaymentRecentItem(
    id = id,
    amount = formatter.format(amount),
    statusLabel =
        nativeString(
            when (status) {
                TransactionStatus.Succeeded -> NativeStringResource(
                    table = "SparkPayment",
                    key = "spark_transaction_status_success"
                )

                TransactionStatus.Failed -> NativeStringResource(
                    table = "SparkPayment",
                    key = "spark_transaction_status_failure"
                )
            }
        ),
    statusTone = status.nativeStatusTone(),
    createdAtMs = createdAtMs,
    supportingText =
        when (status) {
            TransactionStatus.Succeeded -> fee?.let {
                nativeString(
                    NativeStringResource(table = "SparkPayment", key = "spark_transaction_fee"),
                    formatter.format(it)
                )
            }

            TransactionStatus.Failed ->
                getSparkPaymentErrorMessageFor(
                    error ?: PaymentUiError.Unexpected()
                )
        },
    detailState = toDetailUiState().toNativePaymentScreenState(),
    receipt = receipt
)

private fun TransactionStatus.nativeStatusTone(): String = when (this) {
    TransactionStatus.Succeeded -> "success"
    TransactionStatus.Failed -> "failure"
}
