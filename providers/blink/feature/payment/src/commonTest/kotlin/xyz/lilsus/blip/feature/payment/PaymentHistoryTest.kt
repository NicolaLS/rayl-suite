package xyz.lilsus.blip.feature.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager

class PaymentHistoryTest {
    @Test
    fun recentKeepsTenResolvedReceiptsAndOnlyPaidInvoicesPreventResubmission() = runTest {
        val history = PaymentHistory(
            PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope),
            showEstimatedFeeHint = false
        )
        val paid = CompletedPayment(5_000, 1_000, false, false, null)
        repeat(11) { history.record("paid-$it", 5_000, result = paid) }
        history.record("failed", 5_000, error = PaymentUiError.InvalidInvoice())

        assertEquals(10, history.displayItems.value.size)
        assertEquals(TransactionStatus.Failure, history.displayItems.value.first().status)
        assertNull(history.findPaidByPaymentHash("failed"))
        assertNotNull(history.findPaidByPaymentHash("paid-10"))
        assertNull(history.findPaidByPaymentHash("paid-0"))

        history.reset()
        assertTrue(history.displayItems.value.isEmpty())
        assertNull(history.findPaidByPaymentHash("paid-10"))
    }
}
