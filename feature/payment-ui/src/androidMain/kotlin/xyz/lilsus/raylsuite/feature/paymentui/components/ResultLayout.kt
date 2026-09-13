package xyz.lilsus.raylsuite.feature.paymentui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTestTags
import xyz.lilsus.raylsuite.feature.paymentui.R

sealed interface PaymentResultPresentation {
    data class Success(
        val amountPaid: DisplayAmount,
        val feePaid: DisplayAmount,
        val showEstimatedFeeHint: Boolean = false,
        val wasAlreadyPaid: Boolean = false,
        val hasReceipt: Boolean = false
    ) : PaymentResultPresentation

    data class Error(val message: String) : PaymentResultPresentation
}

@Composable
fun ResultLayout(
    result: PaymentResultPresentation,
    modifier: Modifier = Modifier,
    receiptVisible: Boolean = false,
    estimatedFeeHint: String? = null,
    onViewReceipt: () -> Unit = {},
    onContinue: (() -> Unit)? = null
) {
    val formatter = rememberAmountFormatter()
    Column(
        modifier = modifier.testTag(PaymentTestTags.RESULT),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (result) {
            is PaymentResultPresentation.Success -> {
                if (receiptVisible && result.hasReceipt) {
                    PaymentReceiptText()
                } else {
                    if (result.wasAlreadyPaid) {
                        Text(
                            modifier = Modifier.testTag(PaymentTestTags.RESULT_ALREADY_PAID),
                            text = stringResource(R.string.result_already_paid_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.result_already_paid_message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        val paidTitle = stringResource(R.string.result_paid_title)
                        val amountPaid = formatter.format(result.amountPaid)
                        val paidSummary = buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                                append(paidTitle)
                            }
                            append(" ")
                            append(amountPaid)
                        }
                        Text(
                            modifier = Modifier.testTag(PaymentTestTags.RESULT_SUCCESS),
                            text = paidSummary,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.result_paid_fee,
                                formatter.format(result.feePaid)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (result.showEstimatedFeeHint && estimatedFeeHint != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = estimatedFeeHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (result.hasReceipt) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ViewReceiptChip(onClick = onViewReceipt)
                    }
                }
            }

            is PaymentResultPresentation.Error -> {
                Text(
                    modifier = Modifier.testTag(PaymentTestTags.RESULT_ERROR),
                    text = stringResource(R.string.result_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                val shape = MaterialTheme.shapes.large
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            shape
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .fillMaxWidth(0.9f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        onContinue?.let {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = it) {
                Text(text = stringResource(R.string.tap_continue))
            }
        }
    }
}

@Composable
private fun PaymentReceiptText() {
    val receiptBody = buildAnnotatedString {
        append(stringResource(R.string.result_receipt_body_prefix))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.result_receipt_body_preimage))
        }
        append(stringResource(R.string.result_receipt_body_middle))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.result_receipt_body_only))
        }
        append(stringResource(R.string.result_receipt_body_suffix))
    }

    Text(
        text = stringResource(R.string.result_receipt_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        modifier = Modifier.padding(horizontal = 24.dp),
        text = receiptBody,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ViewReceiptChip(onClick: () -> Unit) {
    AssistChip(
        modifier = Modifier.testTag(PaymentTestTags.RESULT_VIEW_RECEIPT),
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Receipt,
                contentDescription = null
            )
        },
        label = {
            Text(
                text = stringResource(R.string.result_view_receipt),
                style = MaterialTheme.typography.labelMedium
            )
        }
    )
}
