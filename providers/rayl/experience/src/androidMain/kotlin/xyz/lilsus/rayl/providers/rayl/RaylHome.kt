package xyz.lilsus.rayl.providers.rayl

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.feature.payment.raylPaymentStringResource
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentMessages
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow

/** The Android tab shell, shared with the other providers. */
@Composable
internal fun RaylTabs(runtime: RaylRuntime) {
    val selectedTab by runtime.tabState.selectedTab.collectAsStateWithLifecycle()
    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) runtime.tabState.select(tab)
        }
    ) { tab ->
        RaylTabContent(runtime, tab)
    }
}

@Composable
private fun RaylTabContent(runtime: RaylRuntime, tab: AppTab) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val selectedTransactionId by runtime.tabState.selectedTransactionId
        .collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator, ::raylPaymentStringResource)
    val messages = rememberPaymentMessages(coordinator, ::raylPaymentStringResource)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )

    when (tab) {
        AppTab.Scan -> PaymentScanFlow(
            state = flowState,
            messageEvents = messages,
            appTitle = runtime.configuration.appName,
            savePrompt = hubState.savePrompt,
            isSubmitting = isSubmitting,
            selectedTransactionId = selectedTransactionId,
            onSelectTransaction = runtime.tabState::selectTransaction,
            onIntent = coordinator::dispatch,
            onHubIntent = runtime.paymentHub::dispatch
        )

        AppTab.Hub -> PaymentHubTab(
            repository = runtime.paymentHubRepository,
            controller = runtime.paymentHub,
            preferredCurrencyCode = { currencyCode }
        )

        AppTab.Settings -> RaylSettingsTab(runtime)
    }
}

@Composable
private fun RaylSettingsTab(runtime: RaylRuntime) {
    var destination by rememberSaveable { mutableStateOf(RaylSettingsDestination.Root) }
    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = RaylSettingsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination != RaylSettingsDestination.Root,
        onBackCompleted = { destination = RaylSettingsDestination.Root }
    )
    when (destination) {
        RaylSettingsDestination.Root -> SettingsFlow(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            legalLinks = runtime.configuration.legalLinks,
            overviewBottomContent = {
                RaylSettingsActions(
                    runtime = runtime,
                    onOpenClub = { destination = RaylSettingsDestination.Club },
                    onAddFunds = { destination = RaylSettingsDestination.AddFunds }
                )
            }
        )

        RaylSettingsDestination.Club -> RaylClubScreen(
            club = runtime.club,
            googleWebClientId = runtime.environment.clubConfig().googleWebClientId,
            onPayFee = runtime::payClubFee,
            onBack = { destination = RaylSettingsDestination.Root }
        )

        RaylSettingsDestination.AddFunds -> RaylAddFundsScreen(
            funding = runtime.funding,
            onBack = {
                runtime.funding.reset()
                destination = RaylSettingsDestination.Root
            }
        )
    }
}

@Composable
private fun RaylSettingsActions(
    runtime: RaylRuntime,
    onOpenClub: () -> Unit,
    onAddFunds: () -> Unit
) {
    val balance by runtime.pocket.balanceSats.collectAsStateWithLifecycle()
    val status by runtime.pocket.status.collectAsStateWithLifecycle()
    val failed = status == PocketStatus.Failed
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    val formatter = rememberAmountFormatter()
    var confirmation by rememberSaveable { mutableStateOf<RaylLeaveConfirmation?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)) {
        RaylClubRow(club = runtime.club, onOpen = onOpenClub)
        AppSettingsRow(
            title = stringResource(R.string.rayl_spending_power),
            subtitle = if (failed) {
                stringResource(R.string.rayl_balance_unavailable)
            } else {
                balance?.let { formatter.format(DisplayAmount(it, DisplayCurrency.Satoshi)) }
                    ?: stringResource(R.string.rayl_balance_updating)
            },
            icon = Icons.Filled.AccountBalanceWallet,
            onClick = if (failed) runtime::retryPocket else onAddFunds
        ) {
            Text(
                text = stringResource(if (failed) R.string.rayl_retry else R.string.rayl_add_funds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
            )
        }
        AppSettingsRow(
            title = stringResource(R.string.rayl_use_another_wallet),
            subtitle = stringResource(R.string.rayl_use_another_wallet_body),
            subtitleMaxLines = 2,
            icon = Icons.AutoMirrored.Filled.Logout,
            onClick = { if (!isSubmitting) confirmation = RaylLeaveConfirmation.Leave }
        )
        if (runtime.canErase) {
            AppSettingsRow(
                title = stringResource(R.string.rayl_erase_pocket),
                subtitle = stringResource(R.string.rayl_erase_pocket_body),
                subtitleMaxLines = 2,
                icon = Icons.Filled.DeleteForever,
                iconTint = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { if (!isSubmitting) confirmation = RaylLeaveConfirmation.Erase }
            )
        }
    }

    confirmation?.let { pending ->
        val erases = pending == RaylLeaveConfirmation.Erase
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    stringResource(
                        if (erases) R.string.rayl_erase_pocket else R.string.rayl_use_another_wallet
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (erases) {
                            R.string.rayl_erase_pocket_body
                        } else {
                            R.string.rayl_use_another_wallet_body
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    colors = if (erases) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                    onClick = {
                        confirmation = null
                        runtime.leave(erase = erases)
                    }
                ) {
                    Text(
                        stringResource(
                            if (erases) R.string.rayl_erase else R.string.rayl_use_another_wallet
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(stringResource(R.string.rayl_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaylAddFundsScreen(funding: RaylFunding, onBack: () -> Unit) {
    val state by funding.state.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rayl_add_funds)) },
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when (val current = state) {
                FundingState.Idle, FundingState.Creating, FundingState.Failed -> {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { value -> amount = value.filter(Char::isDigit).take(9) },
                        label = { Text(stringResource(R.string.rayl_amount_sats)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (current == FundingState.Failed) {
                        Text(
                            text = stringResource(R.string.rayl_invoice_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        enabled = current != FundingState.Creating &&
                            (amount.toLongOrNull() ?: 0) > 0,
                        onClick = { amount.toLongOrNull()?.let(funding::request) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (current == FundingState.Creating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text(stringResource(R.string.rayl_create_invoice))
                        }
                    }
                }

                is FundingState.Awaiting -> {
                    val context = LocalContext.current
                    Text(
                        text = stringResource(R.string.rayl_add_funds_body),
                        textAlign = TextAlign.Center
                    )
                    Image(
                        painter = rememberQrCodePainter(current.invoice.uppercase()),
                        contentDescription = null,
                        modifier = Modifier.size(260.dp)
                    )
                    Text(
                        text = current.invoice,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("Lightning invoice", current.invoice)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.rayl_copy_invoice))
                    }
                    Text(
                        text = stringResource(R.string.rayl_waiting_for_payment),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is FundingState.Received -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        text = stringResource(R.string.rayl_funds_added),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rayl_done))
                    }
                }
            }
        }
    }
}

private enum class RaylSettingsDestination { Root, Club, AddFunds }

private enum class RaylLeaveConfirmation { Leave, Erase }

private data class RaylSettingsNavigationInfo(val destination: RaylSettingsDestination) :
    NavigationEventInfo()
