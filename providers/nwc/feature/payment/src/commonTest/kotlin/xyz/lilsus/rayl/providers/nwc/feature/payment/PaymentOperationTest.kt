package xyz.lilsus.rayl.providers.nwc.feature.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentOperationTest {
    @Test
    fun busyOperationRejectsOverlappingInputWithoutQueuingOrReplacingIt() = runTest {
        val operation = PaymentOperation(backgroundScope)
        val completion = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        operation.launch {
            calls += "first"
            completion.await()
            calls += "completed"
        }
        // The gate is closed before the coroutine starts, including repeated taps in one frame.
        operation.launch { calls += "second" }
        assertTrue(operation.active.value)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        operation.launch { calls += "dismiss" }
        assertEquals(listOf("first"), calls)
        assertTrue(operation.active.value)

        completion.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(operation.active.value)
        operation.launch { calls += "next" }
        testScheduler.runCurrent()
        assertEquals(listOf("first", "completed", "next"), calls)
    }

    @Test
    fun cancelledOperationCannotReleaseTheGateForANewerOperation() = runTest {
        val operation = PaymentOperation(backgroundScope)
        operation.launch { awaitCancellation() }
        testScheduler.runCurrent()
        operation.reset()
        operation.launch { awaitCancellation() }
        testScheduler.runCurrent()
        assertTrue(operation.active.value)
        operation.reset()
        assertFalse(operation.active.value)
    }
}
