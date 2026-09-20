package xyz.lilsus.rayl.providers.blink

import xyz.lilsus.rayl.providers.blink.feature.payment.PaymentDeepLinkEvents

object BlinkDeepLinks {
    fun clear() = PaymentDeepLinkEvents.clear()

    fun emit(uri: String) {
        PaymentDeepLinkEvents.emit(uri)
    }
}
