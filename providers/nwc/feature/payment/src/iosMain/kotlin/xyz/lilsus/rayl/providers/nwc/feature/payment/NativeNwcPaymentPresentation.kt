package xyz.lilsus.rayl.providers.nwc.feature.payment

import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState

/** Nwc-owned projection into the provider-neutral native Scan presentation state. */
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
            wasAlreadyPaid = false
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(getNwcPaymentErrorMessageFor(error))
}

suspend fun SessionTransactionItem.toNativeRecentItem(
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentRecentItem = NativePaymentRecentItem(
    id = id,
    amount = formatter.format(amount),
    statusLabel = nativeString(
        NativeStringResource(
            table = "NwcPayment",
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
                NativeStringResource(table = "NwcPayment", key = "transaction_fee"),
                formatter.format(it)
            )
        }

        TransactionStatus.Failure -> error?.let { getNwcPaymentErrorMessageFor(it) }
    },
    detailState = toDetailUiState().toNativePaymentScreenState(),
    receipt = receipt
)
