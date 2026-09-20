package xyz.lilsus.flint

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.experience.R
import xyz.lilsus.flint.feature.onboarding.FlintOnboardingDestination
import xyz.lilsus.flint.feature.onboarding.flintOnboarding
import xyz.lilsus.flint.feature.payment.rememberPaymentFlowState
import xyz.lilsus.flint.feature.payment.rememberPaymentMessages
import xyz.lilsus.flint.feature.walletconnection.WalletAction
import xyz.lilsus.flint.feature.walletconnection.WalletRemovalConfirmation
import xyz.lilsus.flint.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentRecentScreen
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanScreen
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics
import xyz.lilsus.raylsuite.feature.settings.SettingsEntry
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.ManagedWallet
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletManagementScreen

internal fun NavGraphBuilder.flintHome(
    runtime: FlintRuntime,
    walletViewModel: WalletViewModel,
    performanceDiagnostics: PerformanceDiagnostics?,
    onWalletRemoved: () -> Unit
) {
    composable<FlintDestination.Home> {
        FlintTabs(
            runtime = runtime,
            walletViewModel = walletViewModel,
            performanceDiagnostics = performanceDiagnostics,
            onWalletRemoved = onWalletRemoved
        )
    }
}

/** The Android tab shell. iOS renders [FlintTabContent] inside a native `TabView` instead. */
@Composable
private fun FlintTabs(
    runtime: FlintRuntime,
    walletViewModel: WalletViewModel,
    performanceDiagnostics: PerformanceDiagnostics?,
    onWalletRemoved: () -> Unit
) {
    val tabState = runtime.tabState
    val selectedTab by tabState.selectedTab.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(runtime.paymentCoordinator)

    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) tabState.select(tab)
        },
        recentBadgeCount = flowState.newSessionTransactionCount
    ) { tab ->
        FlintTabContent(
            runtime = runtime,
            walletViewModel = walletViewModel,
            tab = tab,
            performanceDiagnostics = performanceDiagnostics,
            onWalletRemoved = onWalletRemoved
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun FlintTabContent(
    runtime: FlintRuntime,
    walletViewModel: WalletViewModel,
    tab: AppTab,
    performanceDiagnostics: PerformanceDiagnostics?,
    onWalletRemoved: () -> Unit
) {
    val coordinator = runtime.paymentCoordinator
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator)
    val messages = rememberPaymentMessages(coordinator)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )

    when (tab) {
        AppTab.Scan ->
            PaymentScanScreen(
                state = flowState,
                messageEvents = messages,
                appTitle = runtime.configuration.appName,
                savePrompt = hubState.savePrompt,
                onIntent = coordinator::dispatch,
                onHubIntent = runtime.paymentHub::dispatch,
                onOpenTransaction = { id ->
                    if (!coordinator.isSubmitting.value) tabState.openTransaction(id)
                }
            )

        AppTab.Recent ->
            PaymentRecentScreen(
                state = flowState,
                receiptFeeInformation = null,
                selectedTransactionId = selectedTransactionId,
                onSelectTransaction = tabState::selectTransaction,
                onIntent = coordinator::dispatch
            )

        AppTab.Hub ->
            PaymentHubTab(
                repository = runtime.paymentHubRepository,
                controller = runtime.paymentHub,
                preferredCurrencyCode = { currencyCode }
            )

        AppTab.Settings ->
            FlintSettingsTab(
                runtime = runtime,
                walletViewModel = walletViewModel,
                performanceDiagnostics = performanceDiagnostics,
                onWalletRemoved = onWalletRemoved
            )
    }
}

@Composable
private fun FlintSettingsTab(
    runtime: FlintRuntime,
    walletViewModel: WalletViewModel,
    performanceDiagnostics: PerformanceDiagnostics?,
    onWalletRemoved: () -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(FlintSettingsDestination.Root) }
    val walletState by walletViewModel.state.collectAsStateWithLifecycle()
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()

    NavigationEventHandler(
        state = rememberNavigationEventState(
            currentInfo = FlintSettingsNavigationInfo(destination)
        ),
        isForwardEnabled = false,
        isBackEnabled = destination == FlintSettingsDestination.WalletManagement,
        onBackCompleted = { destination = FlintSettingsDestination.Root }
    )

    if (walletState.confirmRemoval) {
        WalletRemovalConfirmation(
            dispatch = { action ->
                if (action != WalletAction.ConfirmRemoval ||
                    !runtime.paymentCoordinator.isSubmitting.value
                ) {
                    walletViewModel.dispatch(action)
                }
            },
            canRemove = !isSubmitting
        )
    }

    LaunchedEffect(walletState.access) {
        if (walletState.access == WalletAccessState.NoWallet) onWalletRemoved()
    }

    when (destination) {
        FlintSettingsDestination.Root ->
            SettingsFlow(
                themePreferences = runtime.themePreferences,
                languageRepository = runtime.languageRepository,
                bitcoinPriceProvider = runtime.bitcoinPriceProvider,
                currencyPreferences = runtime.currencyPreferences,
                paymentPreferences = runtime.paymentPreferences,
                legalLinks = runtime.configuration.legalLinks,
                modifier = Modifier,
                performanceDiagnostics = performanceDiagnostics,
                leadingEntries =
                    listOf(
                        SettingsEntry(
                            id = "wallet",
                            title = stringResource(R.string.settings_wallet_title),
                            subtitle = stringResource(R.string.settings_wallet_subtitle),
                            icon = Icons.Filled.AccountBalanceWallet,
                            onClick = { destination = FlintSettingsDestination.WalletManagement }
                        )
                    )
            )

        FlintSettingsDestination.WalletManagement ->
            WalletManagementScreen(
                wallet =
                    if (walletState.access == WalletAccessState.Connected) {
                        ManagedWallet(
                            id = "spark",
                            title = stringResource(R.string.settings_wallet_title),
                            details =
                                listOf(
                                    stringResource(R.string.settings_wallet_subtitle),
                                    runtime.networkLabel
                                )
                        )
                    } else {
                        null
                    },
                onBack = { destination = FlintSettingsDestination.Root },
                onAddWallet = { destination = FlintSettingsDestination.WalletFlow },
                onRemoveWallet = {
                    if (!runtime.paymentCoordinator.isSubmitting.value) {
                        walletViewModel.dispatch(WalletAction.RequestRemoval)
                    }
                }
            )

        FlintSettingsDestination.WalletFlow -> {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = FlintOnboardingDestination.AddWalletFromSettings
            ) {
                flintOnboarding(
                    navController = navController,
                    onboardingViewModel = runtime.onboardingViewModel,
                    walletViewModel = walletViewModel,
                    onWalletConnected = { destination = FlintSettingsDestination.Root }
                )
            }
        }
    }
}

private enum class FlintSettingsDestination {
    Root,
    WalletManagement,
    WalletFlow
}

private data class FlintSettingsNavigationInfo(val destination: FlintSettingsDestination) :
    NavigationEventInfo()
