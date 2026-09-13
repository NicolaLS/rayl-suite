package xyz.lilsus.blip.feature.payment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymentui.PaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentui.PaymentSessionItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTransactionDetail
import xyz.lilsus.raylsuite.feature.paymentui.localizedMessage

/** Projects Blip's payment state into the Android Compose Scan and Recent render state. */
@Composable
fun rememberPaymentFlowState(coordinator: PaymentCoordinator): PaymentFlowState {
    val uiState by coordinator.uiState.collectAsStateWithLifecycle()
    val sessionTransactions by coordinator.sessionTransactions.collectAsStateWithLifecycle()
    val newSessionTransactionCount by
        coordinator.newSessionTransactionCount.collectAsStateWithLifecycle()
    val transactionDetailNavigationTarget by
        coordinator.transactionDetailNavigationTarget.collectAsStateWithLifecycle()
    val formatter = rememberAmountFormatter()

    return PaymentFlowState(
        payment = uiState.toPaymentScreenState(::blipPaymentErrorMessageFor),
        sessionItems =
            sessionTransactions.map { transaction ->
                PaymentSessionItem(
                    transaction = transaction.toPaymentSessionTransaction(formatter),
                    detail = transaction.toPaymentTransactionDetail()
                )
            },
        newSessionTransactionCount = newSessionTransactionCount,
        transactionDetailNavigationTarget = transactionDetailNavigationTarget
    )
}

/** Localized snackbar text for Blip's payment errors and unsupported-input toasts. */
@Composable
fun rememberPaymentMessages(coordinator: PaymentCoordinator): Flow<String> {
    val context = LocalContext.current
    return remember(coordinator, context) {
        coordinator.events.map { event ->
            when (event) {
                is PaymentEvent.ShowError -> getBlipPaymentErrorMessageFor(event.error, context)
                is PaymentEvent.ShowToast -> event.message.localizedMessage(context)
            }
        }
    }
}

@Composable
private fun SessionTransactionItem.toPaymentTransactionDetail() = PaymentTransactionDetail(
    id = id,
    state = toDetailUiState().toPaymentScreenState(::blipPaymentErrorMessageFor),
    receipt = receipt
)
