package xyz.lilsus.raylsuite.feature.paymentflow

import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.resolveNative
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPriceVerdict
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey

/** Projection into the provider-neutral native Scan presentation state. */
fun PaymentUiState.toNativePaymentScreenState(): PaymentScreenState = when (this) {
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
            fundingSource = fundingSource?.resolveNative(),
            notice = priceNotice?.let { check ->
                check.invoiced?.let { invoiced ->
                    LocalizedText(
                        if (check.verdict == WidgetPriceVerdict.Higher) {
                            PaymentUiTextKey.WidgetPriceHigher
                        } else {
                            PaymentUiTextKey.WidgetPriceLower
                        },
                        currentAmountFormatter().format(invoiced.toDisplayAmount())
                    ).resolveNative()
                }
            }
        )

    is PaymentUiState.Success ->
        PaymentScreenState.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentUiState.Error -> PaymentScreenState.Error(paymentErrorMessage(error))
}

fun SessionTransactionItem.toNativeRecentItem(
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentRecentItem = NativePaymentRecentItem(
    id = id,
    amount = formatter.format(amount),
    statusLabel = LocalizedText(status.labelKey()).resolveNative(),
    statusTone =
        when (status) {
            TransactionStatus.Success,
            TransactionStatus.AlreadyPaid -> "success"

            TransactionStatus.Failure -> "failure"
        },
    createdAtMs = createdAtMs,
    supportingText =
        when (status) {
            TransactionStatus.Success -> fee?.let {
                LocalizedText(PaymentUiTextKey.TransactionFee, formatter.format(it))
                    .resolveNative()
            }

            TransactionStatus.Failure -> paymentErrorMessage(error ?: PaymentUiError.Unexpected())

            TransactionStatus.AlreadyPaid -> null
        },
    detailState = toDetailUiState().toNativePaymentScreenState(),
    receipt = receipt
)

fun paymentErrorMessage(error: PaymentUiError): String = error.toLocalizedText().resolveNative()
