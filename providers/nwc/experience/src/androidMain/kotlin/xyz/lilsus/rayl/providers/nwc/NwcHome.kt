package xyz.lilsus.rayl.providers.nwc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.NwcOnboardingDestination
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.nwcOnboarding
import xyz.lilsus.rayl.providers.nwc.feature.payment.rememberPaymentFlowState
import xyz.lilsus.rayl.providers.nwc.feature.payment.rememberPaymentMessages
import xyz.lilsus.rayl.providers.nwc.feature.walletdetails.NwcWalletDetailsScreen
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletConnection
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.ui.platform.rememberCredentialClipboard
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentRecentScreen
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanScreen
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics
import xyz.lilsus.raylsuite.feature.settings.SettingsEntry
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.ManagedWallet
import xyz.lilsus.raylsuite.feature.walletmanagement.R as WalletR
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletManagementScreen

internal fun NavGraphBuilder.nwcHome(
    runtime: NwcRuntime,
    performanceDiagnostics: PerformanceDiagnostics?
) {
    composable<NwcDestination.Home> {
        NwcTabs(
            runtime = runtime,
            performanceDiagnostics = performanceDiagnostics
        )
    }
}

/** The Android tab shell. iOS renders [NwcTabContent] inside a native `TabView` instead. */
@Composable
private fun NwcTabs(runtime: NwcRuntime, performanceDiagnostics: PerformanceDiagnostics?) {
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
        NwcTabContent(
            runtime = runtime,
            tab = tab,
            performanceDiagnostics = performanceDiagnostics
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun NwcTabContent(
    runtime: NwcRuntime,
    tab: AppTab,
    performanceDiagnostics: PerformanceDiagnostics?
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
                onSelectTransaction = { id ->
                    if (!coordinator.isSubmitting.value) tabState.selectTransaction(id)
                },
                onIntent = coordinator::dispatch
            )

        AppTab.Hub ->
            PaymentHubTab(
                repository = runtime.paymentHubRepository,
                controller = runtime.paymentHub,
                preferredCurrencyCode = { currencyCode }
            )

        AppTab.Settings ->
            NwcSettingsTab(
                runtime = runtime,
                performanceDiagnostics = performanceDiagnostics
            )
    }
}

@Composable
private fun NwcSettingsTab(runtime: NwcRuntime, performanceDiagnostics: PerformanceDiagnostics?) {
    var removeConfirmation by rememberSaveable { mutableStateOf(false) }
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    if (removeConfirmation) {
        AlertDialog(
            onDismissRequest = { removeConfirmation = false },
            title = { Text(stringResource(WalletR.string.settings_manage_wallet_remove)) },
            text = {
                Text(
                    stringResource(
                        if (isSubmitting) {
                            WalletR.string.wallet_removal_payment_active
                        } else {
                            WalletR.string.wallet_removal_payment_warning
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        removeConfirmation = false
                        runtime.removeWallet()
                    }
                ) {
                    Text(stringResource(WalletR.string.settings_manage_wallet_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    removeConfirmation = false
                }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
    var destination by rememberSaveable { mutableStateOf(NwcSettingsDestination.Root) }
    val connection by runtime.nwcWallet.connection.collectAsStateWithLifecycle()
    val walletFlowRequested by runtime.settingsWalletFlow.collectAsStateWithLifecycle()

    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = NwcSettingsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination == NwcSettingsDestination.WalletManagement ||
            destination == NwcSettingsDestination.WalletDetails,
        onBackCompleted = {
            destination = when (destination) {
                NwcSettingsDestination.WalletDetails -> NwcSettingsDestination.WalletManagement
                else -> NwcSettingsDestination.Root
            }
        }
    )

    LaunchedEffect(walletFlowRequested) {
        if (walletFlowRequested) destination = NwcSettingsDestination.WalletFlow
    }

    when (destination) {
        NwcSettingsDestination.Root ->
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
                            title = stringResource(WalletR.string.settings_manage_wallet),
                            subtitle =
                                connection?.let(::formatWalletSubtitle)
                                    ?: stringResource(
                                        WalletR.string.settings_manage_wallet_subtitle
                                    ),
                            icon = Icons.Filled.AccountBalanceWallet,
                            onClick = {
                                destination = NwcSettingsDestination.WalletManagement
                            }
                        )
                    )
            )

        NwcSettingsDestination.WalletManagement ->
            WalletManagementScreen(
                wallet =
                    connection?.let {
                        ManagedWallet(
                            id = it.walletPublicKey,
                            title =
                                it.alias?.takeIf(String::isNotBlank)
                                    ?: it.walletPublicKey
                        )
                    },
                onBack = { destination = NwcSettingsDestination.Root },
                onAddWallet = { runtime.requestSettingsWalletFlow() },
                onRemoveWallet = {
                    removeConfirmation = true
                },
                onWalletDetails = { destination = NwcSettingsDestination.WalletDetails }
            )

        NwcSettingsDestination.WalletDetails -> {
            val current = connection
            if (current == null) {
                LaunchedEffect(Unit) { destination = NwcSettingsDestination.WalletManagement }
            } else {
                NwcWalletDetailsScreen(
                    connection = current,
                    onBack = { destination = NwcSettingsDestination.WalletManagement }
                )
            }
        }

        NwcSettingsDestination.WalletFlow ->
            NwcWalletFlow(
                runtime = runtime,
                onFinished = {
                    runtime.walletFlowHandled()
                    destination = NwcSettingsDestination.Root
                }
            )
    }
}

/**
 * Connecting a wallet from settings, including an NWC link opened while a wallet is already
 * connected. It runs inside the Settings tab so the tab bar stays put.
 */
@Composable
private fun NwcWalletFlow(runtime: NwcRuntime, onFinished: () -> Unit) {
    val clipboard = rememberCredentialClipboard()
    val navController = rememberNavController()
    val pendingDraft = runtime.connectionDraft.uri

    LaunchedEffect(navController, pendingDraft) {
        if (pendingDraft != null) {
            navController.navigate(
                NwcOnboardingDestination.ConfirmWallet(fromSettings = true)
            ) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = NwcOnboardingDestination.AddWalletFromSettings
    ) {
        nwcOnboarding(
            navController = navController,
            nwcWallet = runtime.nwcWallet,
            onboardingViewModel = runtime.onboardingViewModel,
            connectionDraft = runtime.connectionDraft,
            clipboard = clipboard,
            onWalletConnected = onFinished
        )
    }
}

private enum class NwcSettingsDestination {
    Root,
    WalletManagement,
    WalletDetails,
    WalletFlow
}

private data class NwcSettingsNavigationInfo(val destination: NwcSettingsDestination) :
    NavigationEventInfo()

private fun formatWalletSubtitle(connection: NwcWalletConnection): String {
    connection.alias?.takeIf(String::isNotBlank)?.let { return it }
    val publicKey = connection.walletPublicKey
    return if (publicKey.length <= 12) {
        publicKey
    } else {
        "${publicKey.take(6)}…${publicKey.takeLast(4)}"
    }
}
