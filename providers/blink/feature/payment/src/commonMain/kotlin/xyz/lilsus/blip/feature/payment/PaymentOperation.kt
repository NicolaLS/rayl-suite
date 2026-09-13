package xyz.lilsus.blip.feature.payment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One foreground operation; repeated input is ignored rather than queued or replacing a payment. */
internal class PaymentOperation(private val scope: CoroutineScope) {
    private var job: Job? = null
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()

    fun launch(block: suspend () -> Unit) {
        if (job != null) return
        val operation = scope.launch(start = CoroutineStart.LAZY) { block() }
        job = operation
        mutableActive.value = true
        operation.invokeOnCompletion {
            if (job === operation) {
                job = null
                mutableActive.value = false
            }
        }
        operation.start()
    }

    fun reset() {
        val previous = job
        job = null
        mutableActive.value = false
        previous?.cancel()
    }
}
