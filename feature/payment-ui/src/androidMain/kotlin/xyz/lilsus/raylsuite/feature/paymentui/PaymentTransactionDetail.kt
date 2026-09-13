package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.lilsus.raylsuite.feature.paymentui.components.PaymentHero
import xyz.lilsus.raylsuite.feature.paymentui.components.ResultLayout

@Composable
fun PaymentTransactionDetail(
    detail: PaymentTransactionDetail,
    estimatedFeeHint: String?,
    onIntent: (PaymentIntent) -> Unit,
    onBack: () -> Unit
) {
    DisposableEffect(detail.id) {
        onDispose { onIntent(PaymentIntent.DismissResult) }
    }
    val receiptPreimage =
        (detail.state as? PaymentScreenState.Success)
            ?.preimage
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    var showReceipt by remember { mutableStateOf(false) }
    LaunchedEffect(receiptPreimage) { showReceipt = false }
    Scaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PaymentHero(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                phase = detail.state.toHeroPhase(),
                receiptPreimage = receiptPreimage.takeIf { showReceipt }
            )
            ResultLayout(
                modifier = Modifier.fillMaxSize(),
                result = detail.state.toResultPresentation(),
                receiptVisible = showReceipt,
                estimatedFeeHint = estimatedFeeHint,
                onViewReceipt = { showReceipt = true },
                onContinue = onBack
            )
        }
    }
}
