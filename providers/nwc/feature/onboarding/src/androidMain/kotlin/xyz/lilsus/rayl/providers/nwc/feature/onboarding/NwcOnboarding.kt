package xyz.lilsus.rayl.providers.nwc.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.AddNwcWalletEvent
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.AddNwcWalletScreen
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.AddNwcWalletViewModel
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.ConnectNwcWalletDialog
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.ConnectNwcWalletEvent
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.ConnectNwcWalletViewModel
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWallet
import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.rememberCameraPermissionState
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel

@Serializable
sealed interface NwcOnboardingDestination {
    @Serializable
    data object Features : NwcOnboardingDestination

    @Serializable
    data object AutoPay : NwcOnboardingDestination

    @Serializable
    data object Agreement : NwcOnboardingDestination

    @Serializable
    data object WalletInstructions : NwcOnboardingDestination

    @Serializable
    data object AddWallet : NwcOnboardingDestination

    @Serializable
    data object AddWalletFromSettings : NwcOnboardingDestination

    @Serializable
    data class ConfirmWallet(val fromSettings: Boolean) : NwcOnboardingDestination
}

fun nwcEducationDestination(step: String): NwcOnboardingDestination = when (step) {
    "autoPay" -> NwcOnboardingDestination.AutoPay
    "agreement" -> NwcOnboardingDestination.Agreement
    else -> NwcOnboardingDestination.Features
}

fun NavGraphBuilder.nwcOnboarding(
    navController: NavController,
    nwcWallet: NwcWallet,
    onboardingViewModel: OnboardingViewModel,
    connectionDraft: NwcConnectionDraft,
    clipboard: CredentialClipboard,
    connectionOnly: Boolean = false,
    initialEducationStep: String = "features",
    onEducationStepChanged: (String) -> Unit = {},
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null,
    onWalletConnected: () -> Unit
) {
    composable<NwcOnboardingDestination.Features> {
        LaunchedEffect(Unit) { onEducationStepChanged("features") }
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        FeaturesScreen(
            pages = onboardingFeaturePages(),
            currentPage = state.featuresPage,
            stepIndex = OnboardingStep.Features.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onPageChanged = onboardingViewModel::setFeaturesPage,
            onContinue = {
                navController.navigate(NwcOnboardingDestination.AutoPay)
            },
            onBack = null
        )
    }
    composable<NwcOnboardingDestination.AutoPay> {
        LaunchedEffect(Unit) { onEducationStepChanged("autoPay") }
        val onBack = {
            navController.navigate(NwcOnboardingDestination.Features) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        BackHandler(onBack = onBack)
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        val formatter = rememberAmountFormatter()
        AutoPaySettingsScreen(
            body = stringResource(
                R.string.onboarding_autopay_body,
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
                navController.navigate(NwcOnboardingDestination.Agreement)
            },
            onBack = onBack
        )
    }
    composable<NwcOnboardingDestination.Agreement> {
        LaunchedEffect(Unit) { onEducationStepChanged("agreement") }
        val onBack = {
            navController.navigate(NwcOnboardingDestination.AutoPay) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        BackHandler(onBack = onBack)
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        AgreementScreen(
            body = stringResource(
                R.string.onboarding_agreement_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            hasAgreed = state.hasAgreed,
            stepIndex = OnboardingStep.Agreement.index,
            totalSteps = ONBOARDING_STEP_COUNT,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                if (nwcWallet.connection.value == null) {
                    navController.navigate(NwcOnboardingDestination.AddWallet)
                } else {
                    onWalletConnected()
                }
            },
            onBack = onBack
        )
    }
    composable<NwcOnboardingDestination.WalletInstructions> {
        NwcWalletInstructionsScreen(
            stepIndex = 0,
            totalSteps = 0,
            onConnectWallet = { navController.navigateUp() },
            onBack = navController::navigateUp
        )
    }
    composable<NwcOnboardingDestination.AddWallet> {
        AddWalletDestination(
            navController = navController,
            fromSettings = false,
            connectionDraft = connectionDraft,
            clipboard = clipboard,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl
        )
    }
    composable<NwcOnboardingDestination.AddWalletFromSettings> {
        AddWalletDestination(
            navController = navController,
            fromSettings = true,
            connectionDraft = connectionDraft,
            clipboard = clipboard,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl
        )
    }
    dialog<NwcOnboardingDestination.ConfirmWallet> { backStackEntry ->
        val route = backStackEntry.toRoute<NwcOnboardingDestination.ConfirmWallet>()
        ConfirmWalletDestination(
            connectionDraft = connectionDraft,
            clipboard = clipboard,
            nwcWallet = nwcWallet,
            onConnected = {
                connectionDraft.clear()
                when (
                    nwcWalletConnectionOutcome(
                        fromSettings = route.fromSettings,
                        hasAgreed = connectionOnly || onboardingViewModel.uiState.value.hasAgreed
                    )
                ) {
                    NwcWalletConnectionOutcome.ResumeOnboarding ->
                        navController.navigate(nwcEducationDestination(initialEducationStep)) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }

                    NwcWalletConnectionOutcome.CompleteOnboarding,
                    NwcWalletConnectionOutcome.FinishSettings -> onWalletConnected()
                }
            },
            onCancelled = {
                connectionDraft.clear()
                navController.navigateUp()
            }
        )
    }
}

@Composable
private fun AddWalletDestination(
    navController: NavController,
    fromSettings: Boolean,
    connectionDraft: NwcConnectionDraft,
    clipboard: CredentialClipboard,
    privacyPolicyUrl: String?,
    termsUrl: String?
) {
    val viewModel = remember { AddNwcWalletViewModel() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberCameraPermissionState()

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clear()
            if (connectionDraft.uri == null) clipboard.discard()
        }
    }

    fun handleCameraPermissionAction() {
        cameraPermission.refresh()
        when (cameraPermission.authorization) {
            CameraAuthorizationState.NOT_DETERMINED -> cameraPermission.request()

            CameraAuthorizationState.DENIED -> {
                if (cameraPermission.canRequestPermission) {
                    cameraPermission.request()
                } else {
                    cameraPermission.openSettings()
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is AddNwcWalletEvent.Confirm -> {
                    connectionDraft.set(event.uri)
                    navController.navigate(
                        NwcOnboardingDestination.ConfirmWallet(
                            fromSettings = fromSettings
                        )
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AddNwcWalletScreen(
            state = state,
            onBack = if (fromSettings) {
                {
                    viewModel.reset()
                    clipboard.discard()
                    navController.navigateUp()
                }
            } else {
                null
            },
            onShowInstructions = {
                viewModel.reset()
                clipboard.discard()
                navController.navigate(NwcOnboardingDestination.WalletInstructions)
            },
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onUriChange = {
                clipboard.retainFor(it)
                viewModel.updateUri(it)
            },
            onPaste = { viewModel.prefillUriIfValid(clipboard.read()) },
            onSubmit = viewModel::submit,
            onQrCodeScanned = {
                clipboard.discard()
                viewModel.handleScannedValue(it)
            },
            onCameraPermissionAction = ::handleCameraPermissionAction,
            cameraAuthorization = cameraPermission.authorization,
            canRequestCameraPermission = cameraPermission.canRequestPermission
        )
    }
}

@Composable
private fun ConfirmWalletDestination(
    connectionDraft: NwcConnectionDraft,
    clipboard: CredentialClipboard,
    nwcWallet: NwcWallet,
    onConnected: () -> Unit,
    onCancelled: () -> Unit
) {
    val uri = connectionDraft.uri
    if (uri == null) {
        LaunchedEffect(Unit) {
            onCancelled()
        }
        return
    }
    val viewModel = remember(nwcWallet) { ConnectNwcWalletViewModel(nwcWallet) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clear()
            connectionDraft.clear()
            clipboard.discard()
        }
    }
    LaunchedEffect(viewModel, uri) {
        clipboard.retainFor(uri)
        viewModel.load(uri)
        viewModel.events.collectLatest { event ->
            when (event) {
                is ConnectNwcWalletEvent.Success -> {
                    clipboard.clearAfterSaving()
                    onConnected()
                }

                ConnectNwcWalletEvent.Cancelled -> {
                    clipboard.discard()
                    onCancelled()
                }
            }
        }
    }

    ConnectNwcWalletDialog(
        state = state,
        onAliasChange = viewModel::updateAlias,
        onRetryDiscovery = viewModel::retryDiscovery,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel
    )
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

private enum class OnboardingStep(val index: Int) {
    Features(1),
    AutoPay(2),
    Agreement(3)
}

private const val ONBOARDING_STEP_COUNT = 4
