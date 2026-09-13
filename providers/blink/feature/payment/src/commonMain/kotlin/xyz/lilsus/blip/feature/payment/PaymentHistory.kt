package xyz.lilsus.blip.feature.payment

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager

/** Resolved receipts for this process only. In-flight or unconfirmed payments are never history. */
internal class PaymentHistory(
    private val currencyManager: PaymentCurrencyManager,
    private val showEstimatedFeeHint: Boolean
) {
    private val receipts = ArrayDeque<PaymentReceipt>()
    private var nextId = 0L
    private val mutableDisplayItems = MutableStateFlow<List<SessionTransactionItem>>(emptyList())
    val displayItems = mutableDisplayItems.asStateFlow()

    fun record(
        paymentHash: String,
        amountMsats: Long,
        result: CompletedPayment? = null,
        error: PaymentUiError? = null
    ) {
        require((result == null) != (error == null))
        receipts.addFirst(
            PaymentReceipt(
                id = "payment-${nextId++}",
                paymentHash = paymentHash,
                amountMsats = amountMsats,
                createdAtMs = Clock.System.now().toEpochMilliseconds(),
                result = result,
                error = error
            )
        )
        if (receipts.size > MAX_RECENT_PAYMENTS) receipts.removeLast()
        refreshDisplayItems()
    }

    fun findPaidByPaymentHash(paymentHash: String): SessionTransactionItem? {
        val receipt = receipts.firstOrNull { it.paymentHash == paymentHash && it.result != null }
            ?: return null
        return displayItems.value.firstOrNull { it.id == receipt.id }
    }

    fun refreshDisplayItems() {
        val currency = currencyManager.state.value
        mutableDisplayItems.value = receipts.map { receipt ->
            val result = receipt.result
            SessionTransactionItem(
                id = receipt.id,
                amount = currencyManager.convertMsatsToDisplay(receipt.amountMsats, currency),
                status = when {
                    result == null -> TransactionStatus.Failure
                    result.wasAlreadyPaid -> TransactionStatus.AlreadyPaid
                    else -> TransactionStatus.Success
                },
                createdAtMs = receipt.createdAtMs,
                resultAmount = result?.let {
                    currencyManager.convertMsatsToDisplay(it.amountMsats, currency)
                },
                fee = result?.let { currencyManager.convertMsatsToDisplay(it.feeMsats, currency) },
                error = receipt.error,
                showEstimatedFeeHint = showEstimatedFeeHint,
                wasAlreadyPaid = result?.wasAlreadyPaid == true,
                preimage = result?.preimage
            )
        }
    }

    fun reset() {
        receipts.clear()
        nextId = 0
        mutableDisplayItems.value = emptyList()
    }

    private companion object {
        const val MAX_RECENT_PAYMENTS = 10
    }
}

private data class PaymentReceipt(
    val id: String,
    val paymentHash: String,
    val amountMsats: Long,
    val createdAtMs: Long,
    val result: CompletedPayment?,
    val error: PaymentUiError?
)
