package xyz.lilsus.flint.feature.payment

import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount
import xyz.lilsus.raylsuite.feature.paymentui.PaymentReceiptDetails
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState

sealed interface PaymentUiState {
    data object Active : PaymentUiState

    data class Loading(val kind: LoadingKind = LoadingKind.Paying) : PaymentUiState

    data class EnterAmount(
        val entry: ManualAmountUiState,
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentUiState

    data class Confirm(
        val amount: PaymentConfirmationAmount,
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentUiState

    data class Success(
        val amountPaid: DisplayAmount,
        val feePaid: DisplayAmount,
        val wasAlreadyPaid: Boolean = false
    ) : PaymentUiState

    data class Error(val error: PaymentUiError) : PaymentUiState
}

enum class LoadingKind {
    Resolving,
    Paying
}

sealed interface PaymentUiError {
    data class Spark(val error: SparkPaymentError) : PaymentUiError

    data class InvalidInvoice(val reason: String? = null) : PaymentUiError

    data class Lnurl(val reason: String? = null) : PaymentUiError

    data class ExchangeRateUnavailable(val currencyCode: String) : PaymentUiError

    data class Unexpected(val detail: String? = null) : PaymentUiError
}

data class SessionTransactionItem(
    val id: String,
    val amount: DisplayAmount,
    val status: TransactionStatus,
    val createdAtMs: Long,
    val resultAmount: DisplayAmount? = null,
    val fee: DisplayAmount? = null,
    val error: PaymentUiError? = null,
    val wasAlreadyPaid: Boolean = false,
    val receipt: PaymentReceiptDetails? = null
)

enum class TransactionStatus {
    Succeeded,
    Failed
}

sealed interface SparkPaymentError {
    data object WalletUnavailable : SparkPaymentError

    data object SdkUnavailable : SparkPaymentError

    data class Rejected(val detail: String?) : SparkPaymentError

    data object OutcomeUnknown : SparkPaymentError
}

internal fun SessionTransactionItem.toDetailUiState(): PaymentUiState = when (status) {
    TransactionStatus.Succeeded -> {
        val paidAmount = resultAmount ?: amount
        PaymentUiState.Success(
            amountPaid = paidAmount,
            feePaid = fee ?: DisplayAmount(0, paidAmount.currency),
            wasAlreadyPaid = wasAlreadyPaid
        )
    }

    TransactionStatus.Failed -> PaymentUiState.Error(error ?: PaymentUiError.Unexpected())
}
