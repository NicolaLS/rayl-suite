package xyz.lilsus.flint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.feature.onboarding.FlintOnboardingDestination
import xyz.lilsus.flint.feature.onboarding.flintOnboarding
import xyz.lilsus.flint.feature.walletconnection.WalletConnectionScreen
import xyz.lilsus.flint.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.rememberHapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics

@Composable
fun App(host: FlintAppHost, performanceDiagnostics: PerformanceDiagnostics? = null) {
    val appSettings = rememberAppSettings(FLINT_PREFERENCES)
    val haptics = rememberHapticFeedbackManager()
    val runtime =
        rememberRetainedInstance(
            key = FLINT_RUNTIME_KEY,
            factory = { FlintRuntime(host = host, appSettings = appSettings, haptics = haptics) },
            onDispose = FlintRuntime::clear
        )
    val themePreference by
        runtime.themePreferences.preference.collectAsStateWithLifecycle(
            initialValue = ThemePreference.System
        )
    val walletViewModel = viewModel { WalletViewModel(runtime.walletAccess) }
    val walletState by walletViewModel.state.collectAsStateWithLifecycle()
    var navigationReady by remember { mutableStateOf(!walletState.access.isInitialising()) }

    LaunchedEffect(walletState.access) {
        if (!walletState.access.isInitialising()) navigationReady = true
        if (
            walletState.access == WalletAccessState.NoWallet ||
            walletState.access == WalletAccessState.ResetRequired
        ) {
            runtime.resetPaymentSession()
        }
    }
    RaylSuiteTheme(themePreference = themePreference) {
        if (!navigationReady) {
            InitialWalletLoading()
        } else if (walletState.access == WalletAccessState.Removing ||
            walletState.access == WalletAccessState.ResetRequired
        ) {
            WalletConnectionScreen(
                state = walletState,
                onBack = null,
                dispatch = walletViewModel::dispatch
            )
        } else {
            val navController = rememberNavController()
            val startDestination =
                remember {
                    when (walletState.access) {
                        WalletAccessState.Connected -> FlintDestination.Home
                        WalletAccessState.NoWallet -> FlintOnboardingDestination.Welcome
                        else -> FlintOnboardingDestination.WalletRecovery
                    }
                }
            NavHost(
                navController = navController,
                startDestination = startDestination
            ) {
                flintOnboarding(
                    navController = navController,
                    onboardingViewModel = runtime.onboardingViewModel,
                    walletViewModel = walletViewModel,
                    onWalletConnected = {
                        navController.navigate(FlintDestination.Home) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
                flintHome(
                    runtime = runtime,
                    walletViewModel = walletViewModel,
                    performanceDiagnostics = performanceDiagnostics,
                    onWalletRemoved = {
                        navController.navigate(FlintOnboardingDestination.Welcome) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun InitialWalletLoading() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Text(
                text = "Flint",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

internal fun WalletAccessState.isInitialising(): Boolean =
    this == WalletAccessState.Loading || this == WalletAccessState.Connecting

private const val FLINT_RUNTIME_KEY = "flint-runtime"
