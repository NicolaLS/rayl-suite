package xyz.lilsus.rayl.providers.nwc.feature.payment

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object PaymentDeepLinkEvents {
    private val eventsChannel = Channel<String>(capacity = Channel.UNLIMITED)

    val events: Flow<String> = eventsChannel.receiveAsFlow()

    fun emit(uri: String) {
        eventsChannel.trySend(uri)
    }
}
