package xyz.lilsus.flint.feature.payment

import xyz.lilsus.flint.application.payment.AmountRequiredPayment
import xyz.lilsus.flint.application.payment.PaymentActivity
import xyz.lilsus.flint.application.payment.PreparedPayment
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymenthub.HubItemId
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount

/**
 * Holds the mutually exclusive phases of one Flint payment interaction.
 *
 * Wallet attachment and durable attempts outlive this presentation session. Resetting it only
 * drops transient handles and result presentation owned by the coordinator.
 */
internal class PaymentInteractionSession {
    var phase: PaymentInteractionPhase = PaymentInteractionPhase.Idle
        private set

    val activeDraft: ActiveDraft?
        get() = (phase as? PaymentInteractionPhase.Draft)?.draft

    val manualRequest: ManualRequest?
        get() = (phase as? PaymentInteractionPhase.ManualAmount)?.request

    val activeAttemptId: String?
        get() = (phase as? PaymentInteractionPhase.Attempt)?.attemptId

    val visibleActivity: VisibleActivity?
        get() = when (val current = phase) {
            is PaymentInteractionPhase.Attempt -> current.visibleActivity
            is PaymentInteractionPhase.Result -> current.visibleActivity
            else -> null
        }

    fun requestManualAmount(request: ManualRequest) {
        phase = PaymentInteractionPhase.ManualAmount(request)
    }

    fun prepareDraft(draft: ActiveDraft) {
        phase = PaymentInteractionPhase.Draft(draft)
    }

    fun beginAttempt(attemptId: String) {
        phase = PaymentInteractionPhase.Attempt(attemptId)
    }

    fun showActivity(visibleActivity: VisibleActivity) {
        val current = phase
        phase =
            if (
                current is PaymentInteractionPhase.Attempt &&
                current.attemptId == visibleActivity.activity.attemptId
            ) {
                current.copy(visibleActivity = visibleActivity)
            } else {
                PaymentInteractionPhase.Result(visibleActivity)
            }
    }

    fun clearVisibleActivity() {
        phase = when (val current = phase) {
            is PaymentInteractionPhase.Attempt -> current.copy(visibleActivity = null)
            is PaymentInteractionPhase.Result -> PaymentInteractionPhase.Idle
            else -> current
        }
    }

    fun reset() {
        phase = PaymentInteractionPhase.Idle
    }
}

internal sealed interface PaymentInteractionPhase {
    data object Idle : PaymentInteractionPhase

    data class ManualAmount(val request: ManualRequest) : PaymentInteractionPhase

    data class Draft(val draft: ActiveDraft) : PaymentInteractionPhase

    data class Attempt(val attemptId: String, val visibleActivity: VisibleActivity? = null) :
        PaymentInteractionPhase

    data class Result(val visibleActivity: VisibleActivity) : PaymentInteractionPhase
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

internal data class VisibleActivity(val activity: PaymentActivity, val wasAlreadyPaid: Boolean)

/** App-owned link between a Spark attempt and the hub target it was started from. */
internal data class HubTargetContext(
    val targetId: HubItemId?,
    val address: LightningAddress,
    val isPreset: Boolean
)
