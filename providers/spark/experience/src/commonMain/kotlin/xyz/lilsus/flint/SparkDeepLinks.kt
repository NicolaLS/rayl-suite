package xyz.lilsus.flint

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object SparkDeepLinks {
    private val channel = Channel<String>(Channel.CONFLATED)
    internal val events = channel.receiveAsFlow()

    fun emit(uri: String) {
        if (uri.length <= 8 * 1024) channel.trySend(uri)
    }

    fun clear() {
        while (channel.tryReceive().isSuccess) { /* Discard requests on replacement. */ }
    }
}
