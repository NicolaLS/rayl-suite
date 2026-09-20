package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.rayl.providers.spark.application.payment.AmountRequiredPayment
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentReceipt
import xyz.lilsus.rayl.providers.spark.application.payment.PreparedPayment
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymenthub.HubItemId
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount

/**
 * Holds the mutually exclusive phases of one Spark payment interaction.
 *
 * Resetting it drops transient handles and the result presentation owned by the coordinator.
 */
internal class PaymentInteractionSession {
    var phase: PaymentInteractionPhase = PaymentInteractionPhase.Idle
        private set

    val activeDraft: ActiveDraft?
        get() = (phase as? PaymentInteractionPhase.Draft)?.draft

    val manualRequest: ManualRequest?
        get() = (phase as? PaymentInteractionPhase.ManualAmount)?.request

    val visibleReceipt: VisibleReceipt?
        get() = (phase as? PaymentInteractionPhase.Result)?.receipt

    fun requestManualAmount(request: ManualRequest) {
        phase = PaymentInteractionPhase.ManualAmount(request)
    }

    fun prepareDraft(draft: ActiveDraft) {
        phase = PaymentInteractionPhase.Draft(draft)
    }

    fun showResult(receipt: VisibleReceipt) {
        phase = PaymentInteractionPhase.Result(receipt)
    }

    fun reset() {
        phase = PaymentInteractionPhase.Idle
    }
}

internal sealed interface PaymentInteractionPhase {
    data object Idle : PaymentInteractionPhase

    data class ManualAmount(val request: ManualRequest) : PaymentInteractionPhase

    data class Draft(val draft: ActiveDraft) : PaymentInteractionPhase

    data class Result(val receipt: VisibleReceipt) : PaymentInteractionPhase
}

internal data class ActiveDraft(
    val payment: PreparedPayment,
    val targetContext: HubTargetContext?,
    val paymentQuote: PaymentAmountQuote?,
    val confirmationAmount: PaymentConfirmationAmount?,
    val lnurlPayDisplay: LnurlPayDisplay?
)

internal data class ManualRequest(
    val payment: AmountRequiredPayment,
    val targetContext: HubTargetContext?,
    val lnurlPayDisplay: LnurlPayDisplay?
)

internal data class VisibleReceipt(val receipt: PaymentReceipt, val wasAlreadyPaid: Boolean)

/** App-owned link between a Spark payment and the hub target it was started from. */
internal data class HubTargetContext(
    val targetId: HubItemId?,
    val address: LightningAddress,
    val isPreset: Boolean
)
