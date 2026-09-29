package xyz.lilsus.raylsuite.feature.paymenthub.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryRequest
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryResult
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetPurchase
import xyz.lilsus.raylsuite.integration.widget.WidgetClient

/**
 * Follows paid provider orders until they are delivered or failed. Polling backs off and stops
 * after a while; it resumes whenever the tracker is asked again, for example on the next launch.
 */
internal class WidgetDeliveryTracker(
    private val repository: PaymentHubRepository,
    private val client: WidgetClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onUpdate: () -> Unit
) {
    private val jobs = mutableMapOf<String, Job>()

    fun follow(purchaseId: String) {
        if (jobs[purchaseId]?.isActive == true) return
        jobs[purchaseId] = scope.launch {
            var attempt = 0
            while (isActive && attempt < BACKOFF_SECONDS.size) {
                val purchase = repository.hub.value.purchase(purchaseId) ?: return@launch
                if (purchase.delivery.state.terminal || purchase.delivery.lost) return@launch
                delay(BACKOFF_SECONDS[attempt] * 1_000L)
                attempt++
                val retryAfter = check(purchase)
                if (retryAfter != null && attempt < BACKOFF_SECONDS.size) {
                    delay((retryAfter - BACKOFF_SECONDS[attempt]).coerceAtLeast(0) * 1_000L)
                }
            }
        }
    }

    fun resumeUnfinished() {
        val cutoff = clock() - RESUME_WINDOW_MS
        repository.hub.value.purchases
            .filter { !it.delivery.state.terminal && !it.delivery.lost && it.paidAtMs >= cutoff }
            .forEach { follow(it.id) }
    }

    /** Returns the provider's requested wait, if any. */
    private suspend fun check(purchase: WidgetPurchase): Long? {
        val result = client.requestDelivery(
            purchase.deliveryUrl,
            WidgetDeliveryRequest(orderId = purchase.orderId, orderToken = purchase.orderToken)
        )
        val now = clock()
        return when (result) {
            is WidgetDeliveryResult.Report -> {
                repository.updatePurchase(purchase.id) {
                    it.copy(
                        delivery = it.delivery.copy(
                            state = result.state,
                            message = result.message,
                            items = result.items.ifEmpty { it.delivery.items },
                            presentation = result.presentation,
                            checkedAtMs = now
                        )
                    )
                }
                onUpdate()
                result.retryAfterSeconds?.toLong()
            }

            WidgetDeliveryResult.NotFound -> {
                repository.updatePurchase(purchase.id) {
                    it.copy(delivery = it.delivery.copy(lost = true, checkedAtMs = now))
                }
                onUpdate()
                null
            }

            WidgetDeliveryResult.NotResponding -> null
        }
    }

    private companion object {
        // Roughly ten minutes of checks after a payment.
        val BACKOFF_SECONDS = longArrayOf(1, 2, 3, 5, 8, 13, 20, 30, 45, 60, 90, 120, 180)
        const val RESUME_WINDOW_MS = 24L * 60 * 60 * 1_000
    }
}
