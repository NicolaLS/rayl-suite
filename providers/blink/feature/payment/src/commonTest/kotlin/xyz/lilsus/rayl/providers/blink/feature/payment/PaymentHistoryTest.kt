package xyz.lilsus.rayl.providers.blink.feature.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope)
        )
        val paid = CompletedPayment(5_000, 1_000, false, null)
        repeat(11) { history.record("paid-$it", 5_000, result = paid) }
        history.record("failed", 5_000, error = PaymentUiError.InvalidInvoice())

        assertEquals(10, history.displayItems.value.size)
        assertEquals(TransactionStatus.Failure, history.displayItems.value.first().status)
        assertNull(history.displayItems.value.first().receipt)
        assertNull(history.findPaidByPaymentHash("failed"))
        assertNotNull(history.findPaidByPaymentHash("paid-10"))
        assertNull(history.findPaidByPaymentHash("paid-0"))

        history.reset()
        assertTrue(history.displayItems.value.isEmpty())
        assertNull(history.findPaidByPaymentHash("paid-10"))
    }

    @Test
    fun alreadyPaidWithoutOriginalSettlementDoesNotInventAReceipt() = runTest {
        val history = PaymentHistory(
            PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope)
        )
        history.record(
            paymentHash = "already-paid",
            amountMsats = 21_000,
            result = CompletedPayment(0, 0, wasAlreadyPaid = true, preimage = null)
        )

        val transaction = assertNotNull(history.findPaidByPaymentHash("already-paid"))
        assertEquals(TransactionStatus.AlreadyPaid, transaction.status)
        assertNull(transaction.receipt)
        assertEquals(0L, assertNotNull(transaction.resultAmount).minor)
        assertTrue(assertIs<PaymentUiState.Success>(transaction.toDetailUiState()).wasAlreadyPaid)
    }
}
