package xyz.lilsus.blip.feature.payment

import xyz.lilsus.blip.integration.blink.BlinkApiError
import xyz.lilsus.blip.integration.blink.BlinkFundingWallet
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount
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
        val fundingWallet: BlinkFundingWallet,
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentUiState

    data class Success(
        val amountPaid: DisplayAmount,
        val feePaid: DisplayAmount,
        val showEstimatedFeeHint: Boolean = false,
        val wasAlreadyPaid: Boolean = false,
        val preimage: String? = null
    ) : PaymentUiState

    data class Error(val error: PaymentUiError) : PaymentUiState
}

enum class LoadingKind {
    Resolving,
    Paying
}

sealed interface PaymentUiError {
    data object StatusUnknown : PaymentUiError

    data class Blink(val error: BlinkApiError) : PaymentUiError

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
    val showEstimatedFeeHint: Boolean = false,
    val wasAlreadyPaid: Boolean = false,
    val preimage: String? = null
)

enum class TransactionStatus {
    Success,
    AlreadyPaid,
    Failure
}

internal fun SessionTransactionItem.toDetailUiState(): PaymentUiState = when (status) {
    TransactionStatus.Success,
    TransactionStatus.AlreadyPaid -> {
        val paidAmount = resultAmount ?: amount
        PaymentUiState.Success(
            amountPaid = paidAmount,
            feePaid = fee ?: DisplayAmount(0, paidAmount.currency),
            showEstimatedFeeHint = showEstimatedFeeHint && !wasAlreadyPaid,
            wasAlreadyPaid = status == TransactionStatus.AlreadyPaid || wasAlreadyPaid,
            preimage = preimage
        )
    }

    TransactionStatus.Failure -> PaymentUiState.Error(error ?: PaymentUiError.Unexpected())
}
