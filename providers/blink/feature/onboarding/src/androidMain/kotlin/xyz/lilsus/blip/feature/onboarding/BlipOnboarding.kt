package xyz.lilsus.blip.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import xyz.lilsus.blip.feature.walletconnection.AddBlinkWalletEvent
import xyz.lilsus.blip.feature.walletconnection.AddBlinkWalletScreen
import xyz.lilsus.blip.feature.walletconnection.AddBlinkWalletViewModel
import xyz.lilsus.blip.integration.blink.BlinkWallet
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen

@Composable
fun BlipOnboarding(
    step: BlinkOnboardingStep,
    blinkWallet: BlinkWallet,
    onboardingViewModel: OnboardingViewModel,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    onStepChanged: (BlinkOnboardingStep) -> Unit,
    onBackToWelcome: (() -> Unit)?
) {
    val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
    val back: (() -> Unit)? = when (step) {
        BlinkOnboardingStep.AutoPay -> ({ onStepChanged(BlinkOnboardingStep.Features) })
        BlinkOnboardingStep.Agreement -> ({ onStepChanged(BlinkOnboardingStep.AutoPay) })
        else -> null
    }
    BackHandler(enabled = back != null) { back?.invoke() }

    when (step) {
        BlinkOnboardingStep.Welcome -> WelcomeScreen(
            title = stringResource(
                R.string.onboarding_welcome_title,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            subtitle = stringResource(R.string.onboarding_welcome_subtitle_line1),
            description = stringResource(R.string.onboarding_welcome_subtitle_line2),
            onGetStarted = { onStepChanged(BlinkOnboardingStep.Connect) }
        )

        BlinkOnboardingStep.Connect -> AddWalletDestination(
            blinkWallet = blinkWallet,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onBack = onBackToWelcome
        )

        BlinkOnboardingStep.Features -> FeaturesScreen(
            pages = onboardingFeaturePages(),
            currentPage = state.featuresPage,
            stepIndex = 1,
            totalSteps = ONBOARDING_STEP_COUNT,
            onPageChanged = onboardingViewModel::setFeaturesPage,
            onContinue = { onStepChanged(BlinkOnboardingStep.AutoPay) },
            onBack = null
        )

        BlinkOnboardingStep.AutoPay -> {
            val formatter = rememberAmountFormatter()
            AutoPaySettingsScreen(
                body = stringResource(
                    R.string.onboarding_autopay_body,
                    xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                ),
                confirmationMode = state.confirmationMode,
                thresholdSats = state.thresholdSats,
                currencyEquivalent = state.thresholdCurrencyEquivalent?.let(formatter::format),
                stepIndex = 2,
                totalSteps = ONBOARDING_STEP_COUNT,
                onConfirmationModeChanged = onboardingViewModel::setConfirmationMode,
                onThresholdChanged = onboardingViewModel::setThreshold,
                onContinue = { onStepChanged(BlinkOnboardingStep.Agreement) },
                onBack = { onStepChanged(BlinkOnboardingStep.Features) }
            )
        }

        BlinkOnboardingStep.Agreement -> AgreementScreen(
            body = stringResource(
                R.string.onboarding_agreement_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            hasAgreed = state.hasAgreed,
            stepIndex = 3,
            totalSteps = ONBOARDING_STEP_COUNT,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                if (state.hasAgreed) onStepChanged(BlinkOnboardingStep.Complete)
            },
            onBack = { onStepChanged(BlinkOnboardingStep.AutoPay) }
        )

        BlinkOnboardingStep.Complete -> Unit
    }
}

@Composable
private fun AddWalletDestination(
    blinkWallet: BlinkWallet,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    onBack: (() -> Unit)?
) {
    val viewModel = remember(blinkWallet) { AddBlinkWalletViewModel(blinkWallet) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val clipboard = remember(context, blinkWallet) { CredentialClipboard(context) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(viewModel, clipboard) {
        onDispose {
            // Connection state can advance the screen before Success is collected.
            if (blinkWallet.connection.value != null) clipboard.clearAfterSaving()
            clipboard.close()
            viewModel.clear()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                AddBlinkWalletEvent.Success -> clipboard.clearAfterSaving()

                AddBlinkWalletEvent.Cancelled -> {
                    clipboard.discard()
                    onBack?.invoke()
                }
            }
        }
    }
    BackHandler(enabled = showInstructions || onBack != null) {
        if (showInstructions) showInstructions = false else viewModel.cancel()
    }

    if (showInstructions) {
        BlinkWalletInstructionsScreen(
            onConnectWallet = { showInstructions = false },
            onBack = { showInstructions = false }
        )
    } else {
        AddBlinkWalletScreen(
            state = state,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onBack = onBack?.let { viewModel::cancel },
            onShowInstructions = {
                viewModel.reset()
                clipboard.discard()
                showInstructions = true
            },
            onApiKeyChange = {
                clipboard.retainFor(it)
                viewModel.updateApiKey(it)
            },
            onPaste = {
                clipboard.read()?.trim()?.takeIf(String::isNotEmpty)?.let(viewModel::updateApiKey)
            },
            onSubmit = viewModel::submit
        )
    }
}

@Composable
private fun onboardingFeaturePages(): List<OnboardingFeaturePage> = listOf(
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_features_page1_title),
        subtitle = stringResource(R.string.onboarding_features_page1_subtitle),
        body = stringResource(
            R.string.onboarding_features_page1_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_features_page2_title),
        subtitle = stringResource(R.string.onboarding_features_page2_subtitle),
        body = stringResource(
            R.string.onboarding_features_page2_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_features_page3_title),
        subtitle = stringResource(
            R.string.onboarding_features_page3_subtitle,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        ),
        body = stringResource(
            R.string.onboarding_features_page3_body,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        )
    )
)

private const val ONBOARDING_STEP_COUNT = 4
