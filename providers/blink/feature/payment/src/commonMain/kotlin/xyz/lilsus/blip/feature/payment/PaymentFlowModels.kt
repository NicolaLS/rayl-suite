package xyz.lilsus.blip.feature.payment

import fr.acinq.lightning.payment.Bolt11Invoice
import xyz.lilsus.blip.integration.blink.BlinkFundingWallet
import xyz.lilsus.blip.integration.blink.BlinkPaymentOutcome
import xyz.lilsus.raylsuite.core.payment.DynamicPaymentSourceKey
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay

internal data class PreparedPayment(
    val invoice: Bolt11Invoice,
    val amountOverrideMsats: Long?,
    val origin: PendingOrigin,
    val source: PaymentRequestSource,
    val dynamicSourceKey: DynamicPaymentSourceKey? = null,
    val targetContext: HubTargetContext? = null,
    val replacesDynamicGuardId: String? = null,
    val lnurlPayDisplay: LnurlPayDisplay? = null,
    val paymentQuote: PaymentAmountQuote? = null
)

internal data class ExecutablePayment(
    val invoice: Bolt11Invoice,
    val amountOverrideMsats: Long?,
    val fundingWallet: BlinkFundingWallet,
    val fundingAmountCents: Long?,
    val origin: PendingOrigin,
    val dynamicSourceKey: DynamicPaymentSourceKey?,
    val targetContext: HubTargetContext?,
    val replacesDynamicGuardId: String?
)

internal sealed interface AdmissionResult {
    data object Presented : AdmissionResult

    data class Payment(val payment: PreparedPayment) : AdmissionResult

    data class PendingClarification(
        val record: PendingRecord,
        val continuation: PendingRetryContinuation
    ) : AdmissionResult
}

internal sealed interface ConfirmationResult {
    data object Presented : ConfirmationResult

    data class Execute(val payment: ExecutablePayment) : ConfirmationResult
}

internal sealed interface ConfirmationDismissal {
    data object None : ConfirmationDismissal

    data object Active : ConfirmationDismissal

    data object ManualAmount : ConfirmationDismissal
}

internal data class PaymentExecutionAttempt(
    val pendingId: String,
    val invoice: Bolt11Invoice,
    val amountOverrideMsats: Long?,
    val targetContext: HubTargetContext?
)

internal data class PaymentExecutionResult(
    val attempt: PaymentExecutionAttempt,
    val outcome: PaymentExecutionOutcome
)

internal sealed interface PaymentExecutionOutcome {
    data class Provider(val outcome: BlinkPaymentOutcome) : PaymentExecutionOutcome

    data class StatusUnknown(val error: PaymentUiError) : PaymentExecutionOutcome
}
