package xyz.lilsus.raylsuite.feature.paymentsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymentsettings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentSettingsScreen(
    state: PaymentSettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (PaymentConfirmationMode) -> Unit,
    onThresholdChanged: (Long) -> Unit,
    onOfferToSaveNewTargetsChanged: (Boolean) -> Unit,
    onVibrateOnScanChanged: (Boolean) -> Unit,
    onVibrateOnPaymentChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val formatter = rememberAmountFormatter()
    val threshold = DisplayAmount(state.thresholdSats, DisplayCurrency.Satoshi)
    val currencyText =
        state.thresholdCurrencyEquivalent?.let { " (${formatter.format(it)})" }.orEmpty()
    val thresholdText =
        when (state.confirmationMode) {
            PaymentConfirmationMode.Above ->
                stringResource(
                    R.string.settings_payments_confirm_threshold,
                    formatter.format(threshold) + currencyText
                )

            PaymentConfirmationMode.Always ->
                stringResource(R.string.settings_payments_option_always)
        }

    Scaffold(
        modifier = modifier.testTag(PaymentSettingsTestTags.SCREEN),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_payments)) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConfirmationSection(
                state = state,
                thresholdText = thresholdText,
                onModeSelected = onModeSelected,
                onThresholdChanged = onThresholdChanged
            )
            SettingsSection(title = stringResource(R.string.settings_payments_hub_title)) {
                SettingsToggle(
                    label = stringResource(R.string.settings_payments_offer_save_targets),
                    checked = state.offerToSaveNewTargets,
                    onCheckedChange = onOfferToSaveNewTargetsChanged,
                    modifier = Modifier.testTag(PaymentSettingsTestTags.OFFER_TO_SAVE_TARGETS)
                )
            }
            SettingsSection(
                title = stringResource(R.string.settings_payments_haptics_title)
            ) {
                SettingsToggle(
                    label = stringResource(R.string.settings_payments_haptics_scan),
                    checked = state.vibrateOnScan,
                    onCheckedChange = onVibrateOnScanChanged
                )
                SettingsToggle(
                    label = stringResource(R.string.settings_payments_haptics_payment),
                    checked = state.vibrateOnPayment,
                    onCheckedChange = onVibrateOnPaymentChanged
                )
            }
        }
    }
}

@Composable
private fun ConfirmationSection(
    state: PaymentSettingsUiState,
    thresholdText: String,
    onModeSelected: (PaymentConfirmationMode) -> Unit,
    onThresholdChanged: (Long) -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_payments_confirm_label)
    ) {
        Text(
            text = thresholdText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PaymentModeChips(
            selected = state.confirmationMode,
            onSelected = onModeSelected
        )
        if (state.confirmationMode == PaymentConfirmationMode.Above) {
            ThresholdSlider(
                thresholdSats = state.thresholdSats,
                onThresholdChanged = onThresholdChanged
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
private fun PaymentModeChips(
    selected: PaymentConfirmationMode,
    onSelected: (PaymentConfirmationMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        confirmationModeOptions.forEach { (mode, label) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                modifier = Modifier.testTag(mode.testTag),
                label = { Text(stringResource(label)) }
            )
        }
    }
}

@Composable
private fun ThresholdSlider(
    thresholdSats: Long,
    onThresholdChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val thresholds = PaymentPreferences.THRESHOLD_STEPS
    Slider(
        value = PaymentPreferences.thresholdToStepIndex(thresholdSats).toFloat(),
        onValueChange = { index ->
            onThresholdChanged(thresholds[index.toInt()])
        },
        valueRange = 0f..thresholds.lastIndex.toFloat(),
        steps = thresholds.size - 2,
        modifier = modifier
    )
}

private val confirmationModeOptions =
    listOf(
        PaymentConfirmationMode.Always to R.string.settings_payments_option_always,
        PaymentConfirmationMode.Above to R.string.settings_payments_option_above
    )

private val PaymentConfirmationMode.testTag: String
    get() =
        when (this) {
            PaymentConfirmationMode.Always -> PaymentSettingsTestTags.MODE_ALWAYS
            PaymentConfirmationMode.Above -> PaymentSettingsTestTags.MODE_ABOVE
        }

object PaymentSettingsTestTags {
    const val SCREEN = "payment_settings"
    const val MODE_ALWAYS = "payment_settings_mode_always"
    const val MODE_ABOVE = "payment_settings_mode_above"
    const val OFFER_TO_SAVE_TARGETS = "payment_settings_offer_to_save_targets"
}
