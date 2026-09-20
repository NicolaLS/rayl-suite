package xyz.lilsus.rayl.providers.blink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.lilsus.rayl.providers.blink.feature.payment.rememberPaymentFlowState
import xyz.lilsus.rayl.providers.blink.feature.payment.rememberPaymentMessages
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.BlinkWalletSettingsActions
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.BlinkWalletSettingsViewModel
import xyz.lilsus.rayl.providers.blink.ui.R as BlinkUiR
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentRecentScreen
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanScreen
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.R as WalletManagementR

internal fun NavGraphBuilder.blinkHome(
    runtime: BlinkRuntime,
    performanceDiagnostics: PerformanceDiagnostics?,
    onRemoveWallet: () -> Unit
) {
    composable<BlinkDestination.Home> {
        BlinkTabs(
            runtime = runtime,
            performanceDiagnostics = performanceDiagnostics,
            onRemoveWallet = onRemoveWallet
        )
    }
}

/** Blink's Android tab shell. iOS renders its tabs directly with SwiftUI. */
@Composable
private fun BlinkTabs(
    runtime: BlinkRuntime,
    performanceDiagnostics: PerformanceDiagnostics?,
    onRemoveWallet: () -> Unit
) {
    val tabState = runtime.tabState
    val selectedTab by tabState.selectedTab.collectAsStateWithLifecycle()

    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) tabState.select(tab)
        },
        tabs = BLINK_PRIMARY_TABS
    ) { tab ->
        BlinkTabContent(
            runtime = runtime,
            tab = tab,
            performanceDiagnostics = performanceDiagnostics,
            onRemoveWallet = onRemoveWallet
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun BlinkTabContent(
    runtime: BlinkRuntime,
    tab: AppTab,
    performanceDiagnostics: PerformanceDiagnostics?,
    onRemoveWallet: () -> Unit
) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator)
    val messages = rememberPaymentMessages(coordinator)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )
    val receiptFeeInformation = stringResource(BlinkUiR.string.receipt_fee_blink_hint)

    when (tab) {
        // Blink has no Recent tab, so its session history lives behind the Scan screen's button.
        AppTab.Scan -> {
            var showingRecent by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(isSubmitting) {
                if (isSubmitting) {
                    tabState.selectTransaction(null)
                    showingRecent = false
                }
            }
            if (showingRecent) {
                PaymentRecentScreen(
                    state = flowState,
                    receiptFeeInformation = receiptFeeInformation,
                    selectedTransactionId = selectedTransactionId,
                    onSelectTransaction = tabState::selectTransaction,
                    onIntent = coordinator::dispatch,
                    onBack = {
                        tabState.selectTransaction(null)
                        showingRecent = false
                    }
                )
            } else {
                PaymentScanScreen(
                    state = flowState,
                    messageEvents = messages,
                    appTitle = runtime.configuration.appName,
                    savePrompt = hubState.savePrompt,
                    onIntent = coordinator::dispatch,
                    onHubIntent = runtime.paymentHub::dispatch,
                    onOpenTransaction = { id ->
                        if (!coordinator.isSubmitting.value) {
                            tabState.selectTransaction(id)
                            showingRecent = true
                        }
                    },
                    onOpenRecent = if (isSubmitting) {
                        null
                    } else {
                        { if (!coordinator.isSubmitting.value) showingRecent = true }
                    }
                )
            }
        }

        AppTab.Recent ->
            PaymentRecentScreen(
                state = flowState,
                receiptFeeInformation = receiptFeeInformation,
                selectedTransactionId = selectedTransactionId,
                onSelectTransaction = tabState::selectTransaction,
                onIntent = coordinator::dispatch
            )

        AppTab.Hub -> BlinkHubTab(runtime = runtime, currencyCode = currencyCode)

        AppTab.Settings ->
            BlinkSettingsTab(
                runtime = runtime,
                performanceDiagnostics = performanceDiagnostics,
                onRemoveWallet = onRemoveWallet
            )
    }
}

@Composable
private fun BlinkHubTab(runtime: BlinkRuntime, currencyCode: String) {
    PaymentHubTab(
        repository = runtime.paymentHubRepository,
        controller = runtime.paymentHub,
        preferredCurrencyCode = { currencyCode }
    )
}

@Composable
private fun BlinkSettingsTab(
    runtime: BlinkRuntime,
    performanceDiagnostics: PerformanceDiagnostics?,
    onRemoveWallet: () -> Unit
) {
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    val removalMessage = stringResource(
        if (isSubmitting) {
            WalletManagementR.string.wallet_removal_payment_active
        } else {
            WalletManagementR.string.wallet_removal_payment_warning
        }
    )
    val walletSettingsViewModel =
        remember(runtime.blinkWallet) {
            BlinkWalletSettingsViewModel(runtime.blinkWallet)
        }
    val walletSettingsState by walletSettingsViewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(walletSettingsViewModel) {
        onDispose(walletSettingsViewModel::clear)
    }
    SettingsFlow(
        themePreferences = runtime.themePreferences,
        languageRepository = runtime.languageRepository,
        bitcoinPriceProvider = runtime.bitcoinPriceProvider,
        currencyPreferences = runtime.currencyPreferences,
        paymentPreferences = runtime.paymentPreferences,
        legalLinks = runtime.configuration.legalLinks,
        modifier = Modifier,
        performanceDiagnostics = performanceDiagnostics,
        overviewBottomContent = {
            BlinkWalletSettingsActions(
                state = walletSettingsState,
                canRemove = !isSubmitting,
                removalMessage = removalMessage,
                onLoadFundingWallets = walletSettingsViewModel::loadFundingWallets,
                onSelectFundingWallet = walletSettingsViewModel::selectFundingWallet,
                onRemoveWallet = onRemoveWallet
            )
        }
    )
}

private val BLINK_PRIMARY_TABS = listOf(AppTab.Scan, AppTab.Hub, AppTab.Settings)
