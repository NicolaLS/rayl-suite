package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object RaylPaymentLinks {
    private val eventsChannel = Channel<String>(1, BufferOverflow.DROP_OLDEST)
    internal val events: Flow<String> = eventsChannel.receiveAsFlow()

    fun emit(uri: String) {
        uri.trim().takeIf(String::isNotEmpty)?.let(eventsChannel::trySend)
    }

    fun clear() {
        while (eventsChannel.tryReceive().isSuccess) {
            // Discard requests meant for an earlier session.
        }
    }
}
