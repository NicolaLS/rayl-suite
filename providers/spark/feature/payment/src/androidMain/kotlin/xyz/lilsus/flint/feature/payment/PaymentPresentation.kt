package xyz.lilsus.flint.feature.payment

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.lilsus.flint.feature.payment.R
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState
import xyz.lilsus.raylsuite.feature.paymentui.PaymentSessionTransaction
import xyz.lilsus.raylsuite.feature.paymentui.PaymentStatusTone

@Composable
internal fun PaymentUiState.toPaymentScreenState(
    errorMessageFor: @Composable (PaymentUiError) -> String
): PaymentScreenState = when (this) {
    PaymentUiState.Active -> PaymentScreenState.Active

    is PaymentUiState.Loading -> PaymentScreenState.Loading(kind.toPresentation())

    is PaymentUiState.EnterAmount -> PaymentScreenState.EnterAmount(entry, lnurlPayDisplay)

    is PaymentUiState.Confirm -> PaymentScreenState.Confirm(amount, lnurlPayDisplay)

    is PaymentUiState.Success ->
        PaymentScreenState.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(errorMessageFor(error))
}

@Composable
internal fun SessionTransactionItem.toPaymentSessionTransaction(
    formatter: AmountFormatter
): PaymentSessionTransaction {
    val presentation = status.presentation()
    return PaymentSessionTransaction(
        id = id,
        amount = amount,
        statusLabel = presentation.label,
        statusTone = presentation.tone,
        createdAtMs = createdAtMs,
        supportingText =
            when (status) {
                TransactionStatus.Succeeded -> fee?.let {
                    stringResource(R.string.spark_transaction_fee, formatter.format(it))
                }

                TransactionStatus.Failed ->
                    flintPaymentErrorMessageFor(error ?: PaymentUiError.Unexpected())
            }
    )
}

private fun LoadingKind.toPresentation(): PaymentLoadingKind = when (this) {
    LoadingKind.Resolving -> PaymentLoadingKind.Resolving
    LoadingKind.Paying -> PaymentLoadingKind.Paying
}

@Composable
private fun TransactionStatus.presentation(): StatusPresentation = when (this) {
    TransactionStatus.Succeeded ->
        StatusPresentation(
            stringResource(R.string.spark_transaction_status_success),
            PaymentStatusTone.Success
        )

    TransactionStatus.Failed ->
        StatusPresentation(
            stringResource(R.string.spark_transaction_status_failure),
            PaymentStatusTone.Failure
        )
}

private data class StatusPresentation(val label: String, val tone: PaymentStatusTone)
