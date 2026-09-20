package xyz.lilsus.rayl.providers.nwc.feature.payment

import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage

sealed interface PaymentEvent {
    data class ShowError(val error: PaymentUiError) : PaymentEvent

    data class ShowToast(val message: PaymentToastMessage) : PaymentEvent
}
