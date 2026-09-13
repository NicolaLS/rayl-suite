package xyz.lilsus.lasr.feature.payment

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
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentUiState

    data class Success(
        val amountPaid: DisplayAmount,
        val feePaid: DisplayAmount,
        val showEstimatedFeeHint: Boolean = false,
        val preimage: String? = null
    ) : PaymentUiState

    data class Error(val error: PaymentUiError) : PaymentUiState
}

enum class LoadingKind {
    Resolving,
    Paying
}

sealed interface PaymentUiError {
    data class Nwc(val error: NwcPaymentError) : PaymentUiError

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
    val fee: DisplayAmount? = null,
    val error: PaymentUiError? = null,
    val showEstimatedFeeHint: Boolean = false,
    val preimage: String? = null
)

enum class TransactionStatus {
    Success,
    Failure
}

internal fun SessionTransactionItem.toDetailUiState(): PaymentUiState = when (status) {
    TransactionStatus.Success -> {
        PaymentUiState.Success(
            amountPaid = amount,
            feePaid = fee ?: DisplayAmount(0, amount.currency),
            showEstimatedFeeHint = showEstimatedFeeHint,
            preimage = preimage
        )
    }

    TransactionStatus.Failure -> PaymentUiState.Error(error ?: PaymentUiError.Unexpected())
}

sealed interface NwcPaymentError {
    val detail: String?

    data object NetworkUnavailable : NwcPaymentError {
        override val detail: String? = null
    }

    data object MissingWalletConnection : NwcPaymentError {
        override val detail: String? = null
    }

    data class Connection(override val detail: String?) : NwcPaymentError

    data class Rejected(val code: String?, override val detail: String?) : NwcPaymentError

    data class DefinitelyNotSent(override val detail: String?) : NwcPaymentError

    data class OutcomeUnknown(override val detail: String?) : NwcPaymentError

    data class Unexpected(override val detail: String?) : NwcPaymentError
}
