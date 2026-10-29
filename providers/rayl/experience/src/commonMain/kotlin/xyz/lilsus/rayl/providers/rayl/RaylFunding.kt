package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPocket

internal sealed interface FundingState {
    data object Idle : FundingState

    data object Creating : FundingState

    data class Awaiting(val invoice: String, val amountSats: Long) : FundingState

    data class Received(val amountSats: Long) : FundingState

    data object Failed : FundingState
}

/** Pre-loading spending power: an invoice for an amount, paid from any external wallet. */
internal class RaylFunding(
    private val pocket: RaylPocket,
    private val description: String,
    private val scope: CoroutineScope,
    private val onInvoice: (String) -> Unit = {}
) {
    private val mutableState = MutableStateFlow<FundingState>(FundingState.Idle)
    val state: StateFlow<FundingState> = mutableState.asStateFlow()

    init {
        scope.launch {
            pocket.receivedInvoices.collect { invoice ->
                val awaiting = mutableState.value as? FundingState.Awaiting
                if (awaiting?.invoice == invoice) {
                    mutableState.value = FundingState.Received(awaiting.amountSats)
                }
            }
        }
    }

    fun request(amountSats: Long) {
        if (amountSats <= 0 || mutableState.value == FundingState.Creating) return
        mutableState.value = FundingState.Creating
        scope.launch {
            mutableState.value = try {
                val invoice = pocket.createInvoice(amountSats, description)
                onInvoice(invoice)
                FundingState.Awaiting(invoice, amountSats)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                FundingState.Failed
            }
        }
    }

    fun reset() {
        mutableState.value = FundingState.Idle
    }
}
