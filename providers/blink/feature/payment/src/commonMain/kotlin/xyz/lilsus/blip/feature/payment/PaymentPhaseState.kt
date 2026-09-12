package xyz.lilsus.blip.feature.payment

internal sealed interface PaymentAdmissionState {
    data object Idle : PaymentAdmissionState

    data class Admitting(val token: PaymentTaskToken) : PaymentAdmissionState
}

internal class PaymentAdmissionSession {
    var state: PaymentAdmissionState = PaymentAdmissionState.Idle
        private set

    fun begin(token: PaymentTaskToken): Boolean {
        if (state != PaymentAdmissionState.Idle) return false
        state = PaymentAdmissionState.Admitting(token)
        return true
    }

    fun complete(token: PaymentTaskToken) {
        if ((state as? PaymentAdmissionState.Admitting)?.token === token) {
            state = PaymentAdmissionState.Idle
        }
    }

    fun reset() {
        state = PaymentAdmissionState.Idle
    }
}

internal sealed interface PaymentConfirmationState {
    data object Idle : PaymentConfirmationState

    data class Preparing(val token: PaymentTaskToken) : PaymentConfirmationState

    data class Awaiting(val confirmation: ExecutablePayment) : PaymentConfirmationState
}

internal class PaymentConfirmationSession {
    var state: PaymentConfirmationState = PaymentConfirmationState.Idle
        private set

    fun begin(token: PaymentTaskToken): Boolean {
        if (state != PaymentConfirmationState.Idle) return false
        state = PaymentConfirmationState.Preparing(token)
        return true
    }

    fun await(token: PaymentTaskToken, confirmation: ExecutablePayment): Boolean {
        if ((state as? PaymentConfirmationState.Preparing)?.token !== token) return false
        state = PaymentConfirmationState.Awaiting(confirmation)
        return true
    }

    fun finishPreparation(token: PaymentTaskToken) {
        if ((state as? PaymentConfirmationState.Preparing)?.token === token) {
            state = PaymentConfirmationState.Idle
        }
    }

    fun take(): ExecutablePayment? {
        val confirmation = (state as? PaymentConfirmationState.Awaiting)?.confirmation
            ?: return null
        state = PaymentConfirmationState.Idle
        return confirmation
    }

    fun reset() {
        state = PaymentConfirmationState.Idle
    }
}
