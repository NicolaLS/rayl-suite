package xyz.lilsus.flint.feature.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.feature.onboarding.R
import xyz.lilsus.flint.feature.walletconnection.WalletConnectionContent
import xyz.lilsus.flint.feature.walletconnection.WalletConnectionScreen
import xyz.lilsus.flint.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.camera.rememberCameraPermissionState
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingScaffold
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.WalletInstructionsScreen
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen

@Serializable
sealed interface FlintOnboardingDestination {
    @Serializable
    data object Welcome : FlintOnboardingDestination

    @Serializable
    data object Features : FlintOnboardingDestination

    @Serializable
    data object AutoPay : FlintOnboardingDestination

    @Serializable
    data object Agreement : FlintOnboardingDestination

    @Serializable
    data object WalletInstructions : FlintOnboardingDestination

    @Serializable
    data object AddWallet : FlintOnboardingDestination

    @Serializable
    data object AddWalletFromSettings : FlintOnboardingDestination

    @Serializable
    data object WalletRecovery : FlintOnboardingDestination
}

fun NavGraphBuilder.flintOnboarding(
    navController: NavController,
    onboardingViewModel: OnboardingViewModel,
    walletViewModel: WalletViewModel,
    welcomeCompleted: Boolean = false,
    onWalletConnected: () -> Unit
) {
    composable<FlintOnboardingDestination.Welcome> {
        WelcomeScreen(
            title = stringResource(
                R.string.spark_onboarding_welcome_title,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            subtitle = stringResource(R.string.spark_onboarding_welcome_subtitle_line1),
            description = stringResource(R.string.spark_onboarding_welcome_subtitle_line2),
            onGetStarted = { navController.navigate(FlintOnboardingDestination.Features) }
        )
    }
    composable<FlintOnboardingDestination.Features> {
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        val cameraPermission = rememberCameraPermissionState()
        FeaturesScreen(
            pages = onboardingFeaturePages(),
            currentPage = state.featuresPage,
            stepIndex = OnboardingStep.Features.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onPageChanged = onboardingViewModel::setFeaturesPage,
            onContinue = { navController.navigate(FlintOnboardingDestination.AutoPay) },
            onRequestCameraPermission = cameraPermission::request,
            onBack = if (welcomeCompleted) null else ({ navController.navigateUp() })
        )
    }
    composable<FlintOnboardingDestination.AutoPay> {
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        val formatter = rememberAmountFormatter()
        AutoPaySettingsScreen(
            body = stringResource(
                R.string.spark_onboarding_autopay_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            confirmationMode = state.confirmationMode,
            thresholdSats = state.thresholdSats,
            currencyEquivalent = state.thresholdCurrencyEquivalent?.let(formatter::format),
            stepIndex = OnboardingStep.AutoPay.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onConfirmationModeChanged = onboardingViewModel::setConfirmationMode,
            onThresholdChanged = onboardingViewModel::setThreshold,
            onContinue = {
                navController.navigate(FlintOnboardingDestination.Agreement)
            },
            onBack = navController::navigateUp
        )
    }
    composable<FlintOnboardingDestination.Agreement> {
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        AgreementScreen(
            body = stringResource(
                R.string.spark_onboarding_agreement_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            hasAgreed = state.hasAgreed,
            stepIndex = OnboardingStep.Agreement.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                navController.navigate(FlintOnboardingDestination.WalletInstructions)
            },
            onBack = navController::navigateUp
        )
    }
    composable<FlintOnboardingDestination.WalletInstructions> {
        WalletInstructionsScreen(
            title = stringResource(R.string.spark_onboarding_add_wallet_title),
            introduction = stringResource(
                R.string.spark_onboarding_add_wallet_intro,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            steps =
                listOf(
                    AnnotatedString(stringResource(R.string.spark_onboarding_add_wallet_step1)),
                    AnnotatedString(
                        stringResource(
                            R.string.spark_onboarding_add_wallet_step2,
                            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                        )
                    ),
                    AnnotatedString(
                        stringResource(
                            R.string.spark_onboarding_add_wallet_step3,
                            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                        )
                    )
                ),
            stepIndex = OnboardingStep.Wallet.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onConnectWallet = {
                navController.navigate(FlintOnboardingDestination.AddWallet)
            },
            onBack = navController::navigateUp
        )
    }
    composable<FlintOnboardingDestination.AddWallet> {
        OnboardingWalletDestination(
            navController = navController,
            walletViewModel = walletViewModel,
            onWalletConnected = onWalletConnected
        )
    }
    composable<FlintOnboardingDestination.AddWalletFromSettings> {
        val state by walletViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.access) {
            if (state.access == WalletAccessState.Connected) {
                navController.navigateUp()
            }
        }
        WalletConnectionScreen(
            state = state,
            onBack = navController::navigateUp,
            dispatch = walletViewModel::dispatch
        )
    }
    composable<FlintOnboardingDestination.WalletRecovery> {
        val state by walletViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.access) {
            when (state.access) {
                WalletAccessState.Connected -> onWalletConnected()

                WalletAccessState.NoWallet -> {
                    val destination = if (welcomeCompleted) {
                        FlintOnboardingDestination.Features
                    } else {
                        FlintOnboardingDestination.Welcome
                    }
                    navController.navigate(destination) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }

                else -> Unit
            }
        }
        WalletConnectionScreen(
            state = state,
            onBack = null,
            dispatch = walletViewModel::dispatch
        )
    }
}

@Composable
private fun OnboardingWalletDestination(
    navController: NavController,
    walletViewModel: WalletViewModel,
    onWalletConnected: () -> Unit
) {
    val state by walletViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.access) {
        if (state.access == WalletAccessState.Connected) onWalletConnected()
    }
    OnboardingScaffold(
        stepIndex = OnboardingStep.Wallet.index,
        totalSteps = ONBOARDING_STEP_COUNT,
        onBack = navController::navigateUp
    ) {
        WalletConnectionContent(
            state = state,
            dispatch = walletViewModel::dispatch,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun onboardingFeaturePages(): List<OnboardingFeaturePage> = listOf(
    OnboardingFeaturePage(
        title = stringResource(R.string.spark_onboarding_features_page1_title),
        subtitle = stringResource(R.string.spark_onboarding_features_page1_subtitle),
        body = stringResource(
            R.string.spark_onboarding_features_page1_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.spark_onboarding_features_page2_title),
        subtitle = stringResource(R.string.spark_onboarding_features_page2_subtitle),
        body = stringResource(
            R.string.spark_onboarding_features_page2_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.spark_onboarding_features_page3_title),
        subtitle = stringResource(
            R.string.spark_onboarding_features_page3_subtitle,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        ),
        body = stringResource(
            R.string.spark_onboarding_features_page3_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    )
)

private enum class OnboardingStep(val index: Int) {
    Welcome(0),
    Features(1),
    AutoPay(2),
    Agreement(3),
    Wallet(4)
}

private const val ONBOARDING_STEP_COUNT = 5
