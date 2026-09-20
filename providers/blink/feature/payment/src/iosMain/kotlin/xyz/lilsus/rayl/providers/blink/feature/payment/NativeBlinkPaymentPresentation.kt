package xyz.lilsus.rayl.providers.blink.feature.payment

import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState

/** Blink-owned projection into the provider-neutral native Scan presentation state. */
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

    is PaymentUiState.Confirm ->
        PaymentScreenState.Confirm(
            amount = amount,
            lnurlPayDisplay = lnurlPayDisplay,
            fundingSource =
                nativeString(
                    NativeStringResource(
                        table = "BlinkPayment",
                        key = "confirm_payment_funding_wallet"
                    ),
                    fundingWallet.currency.nativeTitle()
                )
        )

    is PaymentUiState.Success ->
        PaymentScreenState.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(getBlinkPaymentErrorMessageFor(error))
}

suspend fun SessionTransactionItem.toNativeRecentItem(
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentRecentItem = NativePaymentRecentItem(
    id = id,
    amount = formatter.format(amount),
    statusLabel = status.nativeStatusLabel(),
    statusTone = status.nativeStatusTone(),
    createdAtMs = createdAtMs,
    supportingText =
        when (status) {
            TransactionStatus.Success -> fee?.let {
                nativeString(
                    NativeStringResource(table = "BlinkPayment", key = "transaction_fee"),
                    formatter.format(it)
                )
            }

            TransactionStatus.Failure ->
                getBlinkPaymentErrorMessageFor(
                    error ?: PaymentUiError.Unexpected()
                )

            TransactionStatus.AlreadyPaid -> null
        },
    detailState = toDetailUiState().toNativePaymentScreenState(),
    receipt = receipt
)

private suspend fun TransactionStatus.nativeStatusLabel(): String = nativeString(
    when (this) {
        TransactionStatus.Success -> NativeStringResource(
            table = "BlinkPayment",
            key = "transaction_status_success"
        )

        TransactionStatus.AlreadyPaid -> NativeStringResource(
            table = "BlinkPayment",
            key = "transaction_status_already_paid"
        )

        TransactionStatus.Failure -> NativeStringResource(
            table = "BlinkPayment",
            key = "transaction_status_failure"
        )
    }
)

private fun TransactionStatus.nativeStatusTone(): String = when (this) {
    TransactionStatus.Success,
    TransactionStatus.AlreadyPaid -> "success"

    TransactionStatus.Failure -> "failure"
}

private suspend fun BlinkWalletCurrency.nativeTitle(): String = nativeString(
    NativeStringResource(
        table = "BlinkPayment",
        key =
            when (this) {
                BlinkWalletCurrency.BTC -> "funding_wallet_bitcoin"
                BlinkWalletCurrency.USD -> "funding_wallet_stablesats"
            }
    )
)
