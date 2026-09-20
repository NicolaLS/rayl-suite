package xyz.lilsus.rayl.providers.nwc.feature.payment

import fr.acinq.lightning.payment.Bolt11Invoice
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay

internal data class PreparedPayment(
    val invoice: Bolt11Invoice,
    val amountOverrideMsats: Long?,
    val source: PaymentRequestSource,
    val isManualEntry: Boolean = false,
    val targetContext: HubTargetContext? = null,
    val lnurlPayDisplay: LnurlPayDisplay? = null,
    val paymentQuote: PaymentAmountQuote? = null
)

internal data class ExecutablePayment(
    val invoice: Bolt11Invoice,
    val amountOverrideMsats: Long?,
    val targetContext: HubTargetContext?,
    val lnurlPayDisplay: LnurlPayDisplay? = null
)

internal sealed interface AdmissionResult {
    data object Presented : AdmissionResult

    data class Payment(val payment: PreparedPayment) : AdmissionResult
}

internal sealed interface ConfirmationResult {
    data object Presented : ConfirmationResult

    data class Execute(val payment: ExecutablePayment) : ConfirmationResult
}
