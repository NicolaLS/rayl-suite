package xyz.lilsus.lasr.feature.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager

class PaymentHistoryTest {
    @Test
    fun recentKeepsTenResolvedReceiptsAndOnlyPaidInvoicesPreventResubmission() = runTest {
        val history = PaymentHistory(
            PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope)
        )
        val paid = CompletedPayment(5_000, 1_000, null)
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
    fun currencyChangesKeepSatsAndReceiptDetails() = runTest {
        val currencyManager = PaymentCurrencyManager(
            BitcoinPriceProvider { 100_000.0 },
            backgroundScope
        )
        val usd = DisplayCurrency.Fiat("USD")
        currencyManager.setPreferredCurrency(usd)
        testScheduler.runCurrent()
        val history = PaymentHistory(currencyManager)
        val proof = "ab".repeat(32)
        history.record(
            paymentHash = "paid",
            amountMsats = 21_000,
            result = CompletedPayment(21_000, 1_000, preimage = proof),
            recipient = "recipient@example.com",
            domain = "example.com"
        )

        val original = assertNotNull(history.displayItems.value.single().receipt)
        assertEquals(DisplayAmount(2, usd), original.amount)
        assertEquals(DisplayAmount(1, usd), original.fee)
        assertEquals(DisplayAmount(21, DisplayCurrency.Satoshi), original.amountSats)
        assertEquals(DisplayAmount(1, DisplayCurrency.Satoshi), original.feeSats)
        assertEquals("recipient@example.com", original.recipient)
        assertEquals("example.com", original.domain)
        assertEquals(proof, original.preimage)

        currencyManager.setPreferredCurrency(DisplayCurrency.Satoshi)
        history.refreshDisplayItems()
        val refreshed = assertNotNull(history.displayItems.value.single().receipt)
        assertEquals(
            original.copy(amount = original.amountSats, fee = original.feeSats),
            refreshed
        )
    }
}
