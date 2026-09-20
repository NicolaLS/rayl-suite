package xyz.lilsus.rayl.providers.blink.feature.payment

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object PaymentDeepLinkEvents {
    private val eventsChannel = Channel<String>(capacity = Channel.CONFLATED)

    val events: Flow<String> = eventsChannel.receiveAsFlow()

    fun emit(uri: String) {
        eventsChannel.trySend(uri)
    }

    fun clear() {
        while (eventsChannel.tryReceive().isSuccess) {
            // Drain the single conflated event.
        }
    }
}
