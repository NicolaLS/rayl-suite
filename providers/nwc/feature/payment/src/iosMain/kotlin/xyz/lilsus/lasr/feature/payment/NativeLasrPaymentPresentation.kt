package xyz.lilsus.lasr.feature.payment

import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState

/** Lasr-owned projection into the provider-neutral native Scan presentation state. */
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
            showEstimatedFeeHint = showEstimatedFeeHint,
            wasAlreadyPaid = false,
            preimage = preimage
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(getLasrPaymentErrorMessageFor(error))
}

suspend fun SessionTransactionItem.toNativeRecentItem(
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentRecentItem = NativePaymentRecentItem(
    id = id,
    amount = formatter.format(amount),
    statusLabel = nativeString(
        NativeStringResource(
            table = "LasrPayment",
            key = when (status) {
                TransactionStatus.Success -> "transaction_status_success"
                TransactionStatus.Failure -> "transaction_status_failure"
            }
        )
    ),
    statusTone = when (status) {
        TransactionStatus.Success -> "success"
        TransactionStatus.Failure -> "failure"
    },
    createdAtMs = createdAtMs,
    supportingText = when (status) {
        TransactionStatus.Success -> fee?.let {
            nativeString(
                NativeStringResource(table = "LasrPayment", key = "transaction_fee"),
                formatter.format(it)
            )
        }

        TransactionStatus.Failure -> error?.let { getLasrPaymentErrorMessageFor(it) }
    },
    detailState = toDetailUiState().toNativePaymentScreenState()
)
