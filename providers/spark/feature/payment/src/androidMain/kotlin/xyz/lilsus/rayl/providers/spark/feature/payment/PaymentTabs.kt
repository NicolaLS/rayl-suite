package xyz.lilsus.rayl.providers.spark.feature.payment

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

/** Projects Spark's payment state into the shared render state used by the Scan and Recent tabs. */
@Composable
fun rememberPaymentFlowState(coordinator: PaymentCoordinator): PaymentFlowState {
    val uiState by coordinator.uiState.collectAsStateWithLifecycle()
    val sessionTransactions by coordinator.sessionTransactions.collectAsStateWithLifecycle()
    val newSessionTransactionCount by
        coordinator.newSessionTransactionCount.collectAsStateWithLifecycle()
    val formatter = rememberAmountFormatter()

    return PaymentFlowState(
        payment = uiState.toPaymentScreenState(::sparkPaymentErrorMessageFor),
        sessionItems =
            sessionTransactions.map { transaction ->
                PaymentSessionItem(
                    transaction = transaction.toPaymentSessionTransaction(formatter),
                    detail = transaction.toPaymentTransactionDetail()
                )
            },
        newSessionTransactionCount = newSessionTransactionCount
    )
}

/** Localized snackbar text for Spark's payment errors and unsupported-input toasts. */
@Composable
fun rememberPaymentMessages(coordinator: PaymentCoordinator): Flow<String> {
    val context = LocalContext.current
    return remember(coordinator, context) {
        coordinator.events.map { event ->
            when (event) {
                is PaymentEvent.ShowError -> getSparkPaymentErrorMessageFor(event.error, context)
                is PaymentEvent.ShowToast -> event.message.localizedMessage(context)
            }
        }
    }
}

@Composable
private fun SessionTransactionItem.toPaymentTransactionDetail() = PaymentTransactionDetail(
    id = id,
    state = toDetailUiState().toPaymentScreenState(::sparkPaymentErrorMessageFor),
    receipt = receipt
)
