package xyz.lilsus.rayl.providers.nwc

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object NwcDeepLinks {
    private val eventsChannel =
        Channel<String>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    internal val events: Flow<String> = eventsChannel.receiveAsFlow()

    fun clear() {
        while (eventsChannel.tryReceive().isSuccess) {
            // Discard connection-owned input.
        }
    }

    fun emit(uri: String?) {
        val normalized = uri?.trim()?.takeIf(String::isNotEmpty) ?: return
        eventsChannel.trySend(normalized)
    }
}
