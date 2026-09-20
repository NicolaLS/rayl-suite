package xyz.lilsus.rayl

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object RaylDeepLinks {
    private val channel = Channel<String>(Channel.CONFLATED)
    internal val events = channel.receiveAsFlow()

    fun emit(uri: String?) {
        uri?.takeIf { it.length <= 8 * 1024 }?.let(channel::trySend)
    }

    internal fun clear() {
        while (channel.tryReceive().isSuccess) { /* Discard inputs on replacement. */ }
    }
}
