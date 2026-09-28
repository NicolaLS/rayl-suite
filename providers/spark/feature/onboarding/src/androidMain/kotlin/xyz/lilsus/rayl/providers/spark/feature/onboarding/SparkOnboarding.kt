package xyz.lilsus.rayl.providers.spark.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.onboarding.R
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletConnectionScreen
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.WalletInstructionsScreen

@Serializable
sealed interface SparkOnboardingDestination {
    @Serializable
    data object Features : SparkOnboardingDestination

    @Serializable
    data object AutoPay : SparkOnboardingDestination

    @Serializable
    data object Agreement : SparkOnboardingDestination

    @Serializable
    data object WalletInstructions : SparkOnboardingDestination

    @Serializable
    data object AddWallet : SparkOnboardingDestination

    @Serializable
    data object AddWalletFromSettings : SparkOnboardingDestination

    @Serializable
    data object WalletRecovery : SparkOnboardingDestination
}

fun sparkEducationDestination(step: String): SparkOnboardingDestination = when (step) {
    "autoPay" -> SparkOnboardingDestination.AutoPay
    "agreement" -> SparkOnboardingDestination.Agreement
    else -> SparkOnboardingDestination.Features
}

fun NavGraphBuilder.sparkOnboarding(
    navController: NavController,
    onboardingViewModel: OnboardingViewModel,
    walletViewModel: WalletViewModel,
    connectionOnly: Boolean = false,
    initialEducationStep: String = "features",
    onEducationStepChanged: (String) -> Unit = {},
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null,
    onWalletConnected: () -> Unit
) {
    composable<SparkOnboardingDestination.Features> {
        LaunchedEffect(Unit) { onEducationStepChanged("features") }
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        FeaturesScreen(
            pages = onboardingFeaturePages(),
            currentPage = state.featuresPage,
            stepIndex = OnboardingStep.Features.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onPageChanged = onboardingViewModel::setFeaturesPage,
            onContinue = { navController.navigate(SparkOnboardingDestination.AutoPay) },
            onBack = null
        )
    }
    composable<SparkOnboardingDestination.AutoPay> {
        LaunchedEffect(Unit) { onEducationStepChanged("autoPay") }
        val onBack = {
            navController.navigate(SparkOnboardingDestination.Features) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        BackHandler(onBack = onBack)
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
                navController.navigate(SparkOnboardingDestination.Agreement)
            },
            onBack = onBack
        )
    }
    composable<SparkOnboardingDestination.Agreement> {
        LaunchedEffect(Unit) { onEducationStepChanged("agreement") }
        val onBack = {
            navController.navigate(SparkOnboardingDestination.AutoPay) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        BackHandler(onBack = onBack)
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
                if (state.hasAgreed) onWalletConnected()
            },
            onBack = onBack
        )
    }
    composable<SparkOnboardingDestination.WalletInstructions> {
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
            stepIndex = 0,
            totalSteps = 0,
            onConnectWallet = { navController.navigateUp() },
            onBack = navController::navigateUp
        )
    }
    composable<SparkOnboardingDestination.AddWallet> {
        OnboardingWalletDestination(
            navController = navController,
            walletViewModel = walletViewModel,
            connectionOnly = connectionOnly,
            initialEducationStep = initialEducationStep,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onWalletConnected = onWalletConnected
        )
    }
    composable<SparkOnboardingDestination.AddWalletFromSettings> {
        val state by walletViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.access) {
            if (state.access == WalletAccessState.Connected) {
                navController.navigateUp()
            }
        }
        WalletConnectionScreen(
            state = state,
            onBack = navController::navigateUp,
            dispatch = walletViewModel::dispatch,
            onShowInstructions = {
                navController.navigate(SparkOnboardingDestination.WalletInstructions)
            },
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl
        )
    }
    composable<SparkOnboardingDestination.WalletRecovery> {
        val state by walletViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.access) {
            when (state.access) {
                WalletAccessState.Connected -> {
                    if (connectionOnly) {
                        onWalletConnected()
                    } else {
                        navController.navigate(sparkEducationDestination(initialEducationStep)) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                }

                WalletAccessState.NoWallet -> {
                    navController.navigate(SparkOnboardingDestination.AddWallet) {
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
    connectionOnly: Boolean,
    initialEducationStep: String,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    onWalletConnected: () -> Unit
) {
    val state by walletViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.access) {
        if (state.access == WalletAccessState.Connected) {
            if (connectionOnly) {
                onWalletConnected()
            } else {
                navController.navigate(sparkEducationDestination(initialEducationStep)) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
    WalletConnectionScreen(
        state = state,
        onBack = null,
        dispatch = walletViewModel::dispatch,
        onShowInstructions = {
            navController.navigate(SparkOnboardingDestination.WalletInstructions)
        },
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl
    )
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
    Features(1),
    AutoPay(2),
    Agreement(3)
}

private const val ONBOARDING_STEP_COUNT = 4
