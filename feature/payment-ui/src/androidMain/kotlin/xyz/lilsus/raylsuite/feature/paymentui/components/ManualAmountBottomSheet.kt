package xyz.lilsus.raylsuite.feature.paymentui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTestTags
import xyz.lilsus.raylsuite.feature.paymentui.R
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState
import xyz.lilsus.raylsuite.feature.paymentui.amount.RangeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAmountBottomSheet(
    state: ManualAmountUiState,
    lnurlPayDisplay: LnurlPayDisplay? = null,
    onKeyPress: (ManualAmountKey) -> Unit,
    onRangeClick: (DisplayAmount) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reviewing by rememberSaveable(state.amount, state.rangeStatus) { mutableStateOf(false) }
    val canContinue = state.amount?.minor?.let { it > 0 } == true &&
        state.rangeStatus == RangeStatus.InRange
    val formatter = rememberAmountFormatter()
    ModalBottomSheet(
        modifier = Modifier.enableTestTagsAsResourceId(),
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PaymentTestTags.MANUAL_AMOUNT_SHEET)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    if (reviewing) R.string.confirm_payment_title else R.string.enter_amount_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            lnurlPayDisplay?.let {
                LnurlPayReviewSection(it)
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (reviewing) {
                state.amount?.let { amount ->
                    Text(
                        text = formatter.format(amount),
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(PaymentTestTags.MANUAL_AMOUNT_REVIEW)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(
                    onClick = { reviewing = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PaymentTestTags.MANUAL_AMOUNT_EDIT_BUTTON)
                ) {
                    Text(stringResource(R.string.edit_amount_button))
                }
            } else {
                AmountInputDisplay(state)

                if (state.min != null || state.max != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    RangeHint(state.min, state.max, onRangeClick)
                }

                RangeFeedback(state.rangeStatus)
                Spacer(modifier = Modifier.height(16.dp))
                AmountKeypad(
                    onKeyPress = onKeyPress,
                    enableDecimal = state.allowDecimal
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    if (reviewing) onSubmit() else reviewing = true
                },
                enabled = canContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(
                        if (reviewing) {
                            PaymentTestTags.MANUAL_AMOUNT_PAY_BUTTON
                        } else {
                            PaymentTestTags.MANUAL_AMOUNT_CONTINUE_BUTTON
                        }
                    )
            ) {
                Text(
                    stringResource(if (reviewing) R.string.pay_button else R.string.continue_button)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AmountInputDisplay(state: ManualAmountUiState) {
    val isActive = state.amount != null ||
        state.hasDecimal ||
        state.rawFraction.isNotEmpty() ||
        state.rawWhole != "0"
    val committedColor = if (isActive) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val ghostColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val amountText = buildAnnotatedString {
        append(committedNumber(state))
        if (state.allowDecimal && state.hasDecimal && state.rawFraction.isEmpty()) {
            withStyle(SpanStyle(color = ghostColor, fontWeight = FontWeight.Normal)) {
                append("0")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PaymentTestTags.MANUAL_AMOUNT_DISPLAY),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = amountText,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = committedColor
                ),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(10.dp))
            CurrencyTag(
                label = currencyLabel(state.currency),
                active = isActive
            )
        }
    }
}

private fun committedNumber(state: ManualAmountUiState): String {
    val whole = formatWhole(state.rawWhole)
    val shouldShowDecimal = state.allowDecimal &&
        (state.hasDecimal || state.rawFraction.isNotEmpty())
    if (!shouldShowDecimal) return whole

    return buildString {
        append(whole)
        append(".")
        append(state.rawFraction)
    }
}

private fun formatWhole(raw: String): String {
    val normalized = raw.ifEmpty { "0" }
    if (normalized.length <= 3) return normalized
    val reversed = normalized.reversed().chunked(3).joinToString(",")
    return reversed.reversed()
}

private fun currencyLabel(currency: DisplayCurrency): String = when (currency) {
    DisplayCurrency.Bitcoin -> "BTC"
    DisplayCurrency.Satoshi -> "sat"
    is DisplayCurrency.Fiat -> currency.iso4217.uppercase()
}

@Composable
private fun RangeFeedback(rangeStatus: RangeStatus) {
    val formatter = rememberAmountFormatter()
    val feedback = when (rangeStatus) {
        RangeStatus.InRange, RangeStatus.Unknown -> null

        is RangeStatus.BelowMin -> stringResource(
            R.string.enter_amount_range_min,
            formatter.format(rangeStatus.min)
        )

        is RangeStatus.AboveMax -> stringResource(
            R.string.enter_amount_range_max,
            formatter.format(rangeStatus.max)
        )
    } ?: return

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = feedback,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CurrencyTag(label: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) colors.primary.copy(alpha = 0.12f) else colors.surfaceVariant,
        contentColor = if (active) colors.primary else colors.onSurfaceVariant
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun RangeHint(
    min: DisplayAmount?,
    max: DisplayAmount?,
    onRangeClick: (DisplayAmount) -> Unit
) {
    val formatter = rememberAmountFormatter()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (min != null) {
            RangePill(
                label = stringResource(
                    R.string.enter_amount_range_min,
                    formatter.format(min)
                ),
                onClick = { onRangeClick(min) }
            )
        }
        if (min != null && max != null) {
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (max != null) {
            RangePill(
                label = stringResource(
                    R.string.enter_amount_range_max,
                    formatter.format(max)
                ),
                onClick = { onRangeClick(max) }
            )
        }
    }
}

@Composable
private fun RangePill(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .heightIn(48.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun AmountKeypad(onKeyPress: (ManualAmountKey) -> Unit, enableDecimal: Boolean) {
    val rows = listOf(
        listOf<ManualAmountKey>(
            ManualAmountKey.Digit(1),
            ManualAmountKey.Digit(2),
            ManualAmountKey.Digit(3)
        ),
        listOf(
            ManualAmountKey.Digit(4),
            ManualAmountKey.Digit(5),
            ManualAmountKey.Digit(6)
        ),
        listOf(
            ManualAmountKey.Digit(7),
            ManualAmountKey.Digit(8),
            ManualAmountKey.Digit(9)
        ),
        listOf(
            ManualAmountKey.Decimal,
            ManualAmountKey.Digit(0),
            ManualAmountKey.Backspace
        )
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 12.dp
        val columns = 3
        val rawButtonSize = (maxWidth - spacing * (columns - 1)) / columns
        val buttonSize = rawButtonSize.coerceAtMost(72.dp)

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        spacing,
                        Alignment.CenterHorizontally
                    )
                ) {
                    row.forEach { key ->
                        val enabled = when (key) {
                            ManualAmountKey.Decimal -> enableDecimal
                            else -> true
                        }
                        AmountKeyButton(
                            key = key,
                            enabled = enabled,
                            onClick = { onKeyPress(key) },
                            size = buttonSize
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountKeyButton(key: ManualAmountKey, enabled: Boolean, onClick: () -> Unit, size: Dp) {
    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .testTag(key.testTag())
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
    ) {
        Text(
            text = when (key) {
                is ManualAmountKey.Digit -> key.value.toString()
                ManualAmountKey.Decimal -> "."
                ManualAmountKey.Backspace -> "<"
            },
            fontSize = 20.sp
        )
    }
}

private fun ManualAmountKey.testTag(): String = when (this) {
    is ManualAmountKey.Digit -> PaymentTestTags.manualAmountDigitKey(value)
    ManualAmountKey.Decimal -> PaymentTestTags.MANUAL_AMOUNT_DECIMAL_KEY
    ManualAmountKey.Backspace -> PaymentTestTags.MANUAL_AMOUNT_BACKSPACE_KEY
}

@Preview
@Composable
private fun ManualAmountBottomSheetPreview() {
    RaylSuiteTheme {
        ManualAmountBottomSheet(
            state = ManualAmountUiState(
                amount = DisplayAmount(12345, DisplayCurrency.Satoshi),
                currency = DisplayCurrency.Satoshi
            ),
            onKeyPress = {},
            onRangeClick = {},
            onSubmit = {},
            onDismiss = {}
        )
    }
}

@Preview
@Composable
private fun ManualAmountBottomSheetPreviewWithRange() {
    RaylSuiteTheme {
        ManualAmountBottomSheet(
            state = ManualAmountUiState(
                amount = null,
                currency = DisplayCurrency.Fiat("usd"),
                min = DisplayAmount(1000, DisplayCurrency.Fiat("usd")),
                max = DisplayAmount(5000, DisplayCurrency.Fiat("usd"))
            ),
            onKeyPress = {},
            onRangeClick = {},
            onSubmit = {},
            onDismiss = {}
        )
    }
}
