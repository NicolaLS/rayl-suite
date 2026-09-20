package xyz.lilsus.rayl.providers.spark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.experience.R
import xyz.lilsus.rayl.providers.spark.feature.onboarding.SparkOnboardingDestination
import xyz.lilsus.rayl.providers.spark.feature.onboarding.sparkOnboarding
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletConnectionScreen
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.LocalProductName
import xyz.lilsus.raylsuite.core.ui.platform.rememberHapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics

@Composable
fun SparkExperience(
    configuration: SparkExperienceConfiguration,
    environment: SparkEnvironment,
    breezApiKey: String? = null,
    performanceDiagnostics: PerformanceDiagnostics? = null,
    onRemoved: () -> Unit,
    onChooseWallet: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit
) {
    val appSettings = rememberAppSettings(configuration.preferencesName)
    val haptics = rememberHapticFeedbackManager()
    val context = LocalContext.current.applicationContext
    val runtimeKey = rememberSaveable { "spark-" + Random.nextLong() }
    val runtime =
        rememberRetainedInstance(
            key = runtimeKey,
            factory = {
                SparkRuntime(
                    host = createAndroidAppHost(context, environment, breezApiKey),
                    configuration = configuration,
                    appSettings = appSettings,
                    haptics = haptics
                )
            },
            onDispose = SparkRuntime::clear,
            releaseOnLeave = true
        )
    val themePreference by
        runtime.themePreferences.preference.collectAsStateWithLifecycle(
            initialValue = ThemePreference.System
        )
    val walletViewModel = runtime.walletViewModel
    val walletState by walletViewModel.state.collectAsStateWithLifecycle()
    var navigationReady by remember { mutableStateOf(!walletState.access.isInitialising()) }

    var exitAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var exitAttempt by remember { mutableStateOf(0) }
    val closeFailed by runtime.closeFailed.collectAsStateWithLifecycle()
    LaunchedEffect(runtime) {
        runtime.removed.collect { removed ->
            if (removed) onRemoved()
        }
    }
    LaunchedEffect(runtime, exitAction, exitAttempt) {
        val action = exitAction ?: return@LaunchedEffect
        try {
            runtime.close()
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // closeFailed drives the retry screen; the disposed experience stays inactive.
        }
    }
    LaunchedEffect(runtime, walletState.access) {
        onConnectionChanged(walletState.access == WalletAccessState.Connected)
        if (!walletState.access.isInitialising()) navigationReady = true
        if (
            walletState.access == WalletAccessState.NoWallet ||
            walletState.access == WalletAccessState.ResetRequired
        ) {
            runtime.resetPaymentSession()
        }
    }
    CompositionLocalProvider(LocalProductName provides configuration.appName) {
        RaylSuiteTheme(themePreference = themePreference) {
            if (exitAction != null) {
                Column(modifier = Modifier.safeDrawingPadding().padding(24.dp)) {
                    if (closeFailed) {
                        Text(stringResource(R.string.spark_session_close_failed))
                        TextButton(onClick = { exitAttempt += 1 }) {
                            Text(stringResource(R.string.spark_session_close_retry))
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            } else if (!navigationReady) {
                InitialWalletLoading(configuration.appName)
            } else if (walletState.access == WalletAccessState.Removing ||
                walletState.access == WalletAccessState.ResetRequired
            ) {
                WalletConnectionScreen(
                    state = walletState,
                    onBack = null,
                    dispatch = walletViewModel::dispatch
                )
            } else {
                val canChooseWallet = runtime.canCancelSetup
                val cancellationInsets = if (canChooseWallet) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
                } else {
                    Modifier
                }
                Column(modifier = cancellationInsets) {
                    if (canChooseWallet) {
                        TextButton(
                            onClick = { if (runtime.canCancelSetup) exitAction = onChooseWallet }
                        ) {
                            Text(stringResource(R.string.spark_choose_another_wallet))
                        }
                    }
                    val navController = rememberNavController()
                    val startDestination =
                        remember {
                            when (walletState.access) {
                                WalletAccessState.Connected -> SparkDestination.Home
                                WalletAccessState.NoWallet -> SparkOnboardingDestination.Features
                                else -> SparkOnboardingDestination.WalletRecovery
                            }
                        }
                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        sparkOnboarding(
                            navController = navController,
                            onboardingViewModel = runtime.onboardingViewModel,
                            walletViewModel = walletViewModel,
                            onWalletConnected = {
                                navController.navigate(SparkDestination.Home) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                        sparkHome(
                            runtime = runtime,
                            walletViewModel = walletViewModel,
                            performanceDiagnostics = performanceDiagnostics,
                            onWalletRemoved = {
                                navController.navigate(SparkOnboardingDestination.Features) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialWalletLoading(appName: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Text(
                text = appName,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

internal fun WalletAccessState.isInitialising(): Boolean =
    this == WalletAccessState.Loading || this == WalletAccessState.Connecting
