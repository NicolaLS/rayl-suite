package xyz.lilsus.rayl.providers.blink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.feature.onboarding.BlinkOnboardingStep
import xyz.lilsus.rayl.providers.blink.feature.onboarding.nativeBlinkInstructionProgress
import xyz.lilsus.rayl.providers.blink.feature.onboarding.nativeBlinkOnboardingText
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.AddBlinkWalletEvent
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.AddBlinkWalletViewModel
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.nativeBlinkWalletConnectionText
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.rayl.providers.blink.ui.nativeBlinkErrorMessageFor
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingThresholdLabel
import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

data class BlinkNativeOnboardingPage(
    val title: String,
    val subtitle: String,
    val body: String,
    val imageName: String?
)

data class BlinkNativeOnboardingSnapshot(
    val step: String,
    val canGoBack: Boolean,
    val stepIndex: Int,
    val stepCount: Int,
    val backTitle: String,
    val welcomeTitle: String,
    val welcomeSubtitle: String,
    val welcomeDescription: String,
    val getStartedTitle: String,
    val featurePages: List<BlinkNativeOnboardingPage>,
    val featurePage: Int,
    val featuresNextTitle: String,
    val autoPayTitle: String,
    val autoPayBody: String,
    val autoPayAlwaysTitle: String,
    val autoPayThresholdTitle: String,
    val autoPayThresholdLabel: String,
    val autoPayHint: String,
    val autoPayNextTitle: String,
    val confirmationMode: String,
    val thresholdIndex: Int,
    val thresholdStepCount: Int,
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionPages: List<BlinkNativeOnboardingPage>,
    val instructionPage: Int,
    val instructionProgress: String,
    val previousStepTitle: String,
    val nextStepTitle: String,
    val dashboardTitle: String,
    val dashboardUrl: String,
    val enterKeyTitle: String,
    val walletTitle: String,
    val walletDescription: String,
    val walletInstructionsTitle: String,
    val connectionNotice: String,
    val privacyTitle: String,
    val privacyPolicyUrl: String?,
    val termsTitle: String,
    val termsUrl: String?,
    val apiKeyLabel: String,
    val apiKeyPlaceholder: String,
    val showApiKeyTitle: String,
    val hideApiKeyTitle: String,
    val pasteTitle: String,
    val connectTitle: String,
    val apiKey: String,
    val canConnect: Boolean,
    val isConnecting: Boolean,
    val connectionError: String?
)

/**
 * Blink-owned state bridge for native onboarding. Kotlin retains provider behavior and settings;
 * SwiftUI receives only localized values to render and sends user intent back here.
 */
class BlinkNativeOnboardingController internal constructor(
    private val onboarding: OnboardingViewModel,
    private val blinkWallet: BlinkWallet,
    languageChanges: Flow<*>,
    private val appName: String,
    private val welcomeCompleted: Boolean,
    private val legalLinks: SettingsLegalLinks,
    private val progress: BlinkOnboardingState,
    private val canConnectWallet: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val addWallet = AddBlinkWalletViewModel(blinkWallet)
    private val clipboard = CredentialClipboard()
    private val snapshot = MutableStateFlow<BlinkNativeOnboardingSnapshot?>(null)
    private val observers = mutableSetOf<(BlinkNativeOnboardingSnapshot) -> Unit>()
    private var showingInstructions = false
    private var currentInstructionPage = 0

    init {
        scope.launch {
            combine(
                onboarding.uiState,
                addWallet.uiState,
                languageChanges,
                progress.step,
                blinkWallet.connection
            ) { _, _, _, _, _ -> Unit }
                .collect { publishSnapshot() }
        }
        scope.launch {
            addWallet.events.collect { event ->
                when (event) {
                    AddBlinkWalletEvent.Success -> {
                        clipboard.clearAfterSaving()
                        clearCredentialSnapshot()
                        showingInstructions = false
                    }

                    AddBlinkWalletEvent.Cancelled -> {
                        clipboard.discard()
                        clearCredentialSnapshot()
                        progress.moveTo(BlinkOnboardingStep.Welcome)
                    }
                }
                publishSnapshot()
            }
        }
    }

    fun observe(onChange: (BlinkNativeOnboardingSnapshot) -> Unit): () -> Unit {
        if (!scope.isActive) {
            snapshot.value?.let(onChange)
            return {}
        }
        observers += onChange
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return {
            observers -= onChange
            job.cancel()
        }
    }

    fun continueWelcome() {
        progress.moveTo(BlinkOnboardingStep.Connect)
    }

    fun setFeaturePage(page: Int) {
        onboarding.setFeaturesPage(page)
    }

    fun continueFeatures() {
        progress.moveTo(BlinkOnboardingStep.AutoPay)
    }

    fun setConfirmationMode(value: String) {
        onboarding.setConfirmationMode(
            if (value == CONFIRMATION_ALWAYS) {
                PaymentConfirmationMode.Always
            } else {
                PaymentConfirmationMode.Above
            }
        )
    }

    fun setThresholdIndex(index: Int) {
        PaymentPreferences.THRESHOLD_STEPS.getOrNull(index)?.let(onboarding::setThreshold)
    }

    fun continueAutoPay() {
        progress.moveTo(BlinkOnboardingStep.Agreement)
    }

    fun setAgreement(agreed: Boolean) {
        onboarding.setAgreement(agreed)
    }

    fun continueAgreement() {
        if (onboarding.uiState.value.hasAgreed) progress.moveTo(BlinkOnboardingStep.Complete)
    }

    fun setInstructionPage(page: Int) {
        currentInstructionPage = page.coerceIn(0, INSTRUCTION_PAGE_COUNT - 1)
        refresh()
    }

    fun showWalletConnection() {
        showingInstructions = false
        refresh()
    }

    fun showWalletInstructions() {
        if (blinkWallet.connection.value != null || addWallet.uiState.value.isSaving) return
        addWallet.reset()
        clipboard.discard()
        clearCredentialSnapshot()
        showingInstructions = true
        refresh()
    }

    fun updateApiKey(apiKey: String) {
        if (addWallet.uiState.value.isSaving) return
        clipboard.retainFor(apiKey)
        addWallet.updateApiKey(apiKey)
    }

    fun pasteApiKey() {
        if (addWallet.uiState.value.isSaving) return
        clipboard.read()?.trim()?.takeIf(String::isNotEmpty)?.let(addWallet::updateApiKey)
    }

    fun connectWallet() {
        if (canConnectWallet() && blinkWallet.connection.value == null) addWallet.submit()
    }

    fun back() {
        when (currentStep()) {
            STEP_AUTO_PAY -> progress.moveTo(BlinkOnboardingStep.Features)
            STEP_AGREEMENT -> progress.moveTo(BlinkOnboardingStep.AutoPay)
            STEP_INSTRUCTIONS -> showWalletConnection()
            STEP_WALLET -> if (canReturnToWelcome()) addWallet.cancel()
        }
    }

    fun clear() {
        addWallet.clear()
        clipboard.discard()
        clearCredentialSnapshot()
        observers.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun clearCredentialSnapshot() {
        val cleared = snapshot.value?.copy(
            apiKey = "",
            canConnect = false,
            isConnecting = false,
            connectionError = null
        ) ?: return
        snapshot.value = cleared
        // Disposal can cancel Flow collection before Swift receives the cleared snapshot.
        observers.toList().forEach { it(cleared) }
    }

    private fun currentStep(): String = when (
        progress.step.value.destination(blinkWallet.connection.value != null)
    ) {
        BlinkOnboardingStep.Welcome -> STEP_WELCOME
        BlinkOnboardingStep.Connect -> if (showingInstructions) STEP_INSTRUCTIONS else STEP_WALLET
        BlinkOnboardingStep.Features -> STEP_FEATURES
        BlinkOnboardingStep.AutoPay -> STEP_AUTO_PAY
        BlinkOnboardingStep.Agreement -> STEP_AGREEMENT
        BlinkOnboardingStep.Complete -> "complete"
    }

    private fun canReturnToWelcome(): Boolean =
        !welcomeCompleted && progress.step.value == BlinkOnboardingStep.Connect

    private fun refresh() {
        scope.launch { publishSnapshot() }
    }

    private suspend fun publishSnapshot() {
        val state = onboarding.uiState.value
        val walletState = addWallet.uiState.value
        val common = nativeOnboardingText()
        val blink = nativeBlinkOnboardingText(appName)
        val wallet = nativeBlinkWalletConnectionText(appName)
        val formatter = currentAmountFormatter()
        val sats =
            formatter.format(
                DisplayAmount(state.thresholdSats, DisplayCurrency.Satoshi)
            )
        val thresholdAmount =
            state.thresholdCurrencyEquivalent
                ?.let(formatter::format)
                ?.let { "$sats ($it)" }
                ?: sats
        val instructionPages =
            blink.instructions.map { page ->
                BlinkNativeOnboardingPage(
                    title = page.title,
                    subtitle = page.subtitle,
                    body = page.body,
                    imageName = page.imageName
                )
            }
        val currentStep = currentStep()
        snapshot.value =
            BlinkNativeOnboardingSnapshot(
                step = currentStep,
                canGoBack = currentStep == STEP_AUTO_PAY || currentStep == STEP_AGREEMENT ||
                    currentStep == STEP_INSTRUCTIONS ||
                    (currentStep == STEP_WALLET && canReturnToWelcome()),
                stepIndex = stepIndex(currentStep),
                stepCount = ONBOARDING_STEP_COUNT,
                backTitle = common.back,
                welcomeTitle = blink.welcomeTitle,
                welcomeSubtitle = blink.welcomeSubtitle,
                welcomeDescription = blink.welcomeDescription,
                getStartedTitle = common.getStarted,
                featurePages =
                    blink.features.map { page ->
                        BlinkNativeOnboardingPage(
                            title = page.title,
                            subtitle = page.subtitle,
                            body = page.body,
                            imageName = page.imageName
                        )
                    },
                featurePage = state.featuresPage,
                featuresNextTitle = common.featuresContinue,
                autoPayTitle = common.autoPayTitle,
                autoPayBody = blink.autoPayBody,
                autoPayAlwaysTitle = common.autoPayAlways,
                autoPayThresholdTitle = common.autoPayThreshold,
                autoPayThresholdLabel = nativeOnboardingThresholdLabel(thresholdAmount),
                autoPayHint = common.autoPayHint,
                autoPayNextTitle = common.autoPayContinue,
                confirmationMode =
                    if (state.confirmationMode == PaymentConfirmationMode.Always) {
                        CONFIRMATION_ALWAYS
                    } else {
                        CONFIRMATION_ABOVE
                    },
                thresholdIndex = PaymentPreferences.thresholdToStepIndex(state.thresholdSats),
                thresholdStepCount = PaymentPreferences.THRESHOLD_STEPS.size,
                agreementTitle = common.agreementTitle,
                agreementBody = blink.agreementBody,
                agreementCheckboxTitle = common.agreementCheckbox,
                agreementNextTitle = common.agreementContinue,
                hasAgreed = state.hasAgreed,
                instructionsTitle = blink.instructionsTitle,
                instructionsIntro = blink.instructionsIntro,
                instructionPages = instructionPages,
                instructionPage = currentInstructionPage,
                instructionProgress =
                    nativeBlinkInstructionProgress(
                        currentInstructionPage + 1,
                        instructionPages.size
                    ),
                previousStepTitle = blink.previousStep,
                nextStepTitle = blink.nextStep,
                dashboardTitle = blink.dashboardButton,
                dashboardUrl = BLINK_DASHBOARD_URL,
                enterKeyTitle = blink.enterKeyButton,
                walletTitle = wallet.title,
                walletDescription = wallet.description,
                walletInstructionsTitle = wallet.instructions,
                connectionNotice = wallet.connectionNotice,
                privacyTitle = wallet.privacy,
                privacyPolicyUrl = legalLinks.privacyPolicyUrl,
                termsTitle = wallet.terms,
                termsUrl = legalLinks.termsUrl,
                apiKeyLabel = wallet.apiKeyLabel,
                apiKeyPlaceholder = wallet.apiKeyPlaceholder,
                showApiKeyTitle = wallet.showApiKey,
                hideApiKeyTitle = wallet.hideApiKey,
                pasteTitle = wallet.paste,
                connectTitle = wallet.connect,
                apiKey = walletState.apiKey,
                canConnect =
                    walletState.canSubmit && canConnectWallet() &&
                        blinkWallet.connection.value == null,
                isConnecting = walletState.isSaving,
                connectionError = walletState.error?.let { nativeBlinkErrorMessageFor(it) }
            )
    }

    private fun stepIndex(step: String): Int = when (step) {
        STEP_FEATURES -> 1
        STEP_AUTO_PAY -> 2
        STEP_AGREEMENT -> 3
        else -> 0
    }

    private companion object {
        const val STEP_WELCOME = "welcome"
        const val STEP_FEATURES = "features"
        const val STEP_AUTO_PAY = "autoPay"
        const val STEP_AGREEMENT = "agreement"
        const val STEP_INSTRUCTIONS = "instructions"
        const val STEP_WALLET = "wallet"
        const val CONFIRMATION_ALWAYS = "always"
        const val CONFIRMATION_ABOVE = "above"
        const val ONBOARDING_STEP_COUNT = 4
        const val INSTRUCTION_PAGE_COUNT = 4
        const val BLINK_DASHBOARD_URL = "https://dashboard.blink.sv/api-keys"
    }
}
