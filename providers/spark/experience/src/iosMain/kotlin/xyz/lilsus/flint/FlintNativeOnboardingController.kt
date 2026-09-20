package xyz.lilsus.flint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.flint.application.wallet.CredentialProblemKind
import xyz.lilsus.flint.application.wallet.WalletAccess
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.feature.onboarding.NativeFlintOnboardingPage
import xyz.lilsus.flint.feature.onboarding.nativeFlintOnboardingText
import xyz.lilsus.flint.feature.walletconnection.WalletAction
import xyz.lilsus.flint.feature.walletconnection.WalletMessage
import xyz.lilsus.flint.feature.walletconnection.WalletViewModel
import xyz.lilsus.flint.feature.walletconnection.nativeFlintWalletConnectionText
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingThresholdLabel

data class FlintNativeOnboardingPage(val title: String, val subtitle: String, val body: String)

data class FlintNativeOnboardingSnapshot(
    val step: String,
    val stepIndex: Int,
    val stepCount: Int,
    val canGoBack: Boolean,
    val backTitle: String,
    val welcomeTitle: String,
    val welcomeSubtitle: String,
    val welcomeDescription: String,
    val getStartedTitle: String,
    val featurePages: List<FlintNativeOnboardingPage>,
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
    val instructionSteps: List<String>,
    val connectWalletTitle: String,
    val walletKind: String,
    val walletTitle: String,
    val walletBody: String,
    val phraseLabel: String,
    val phraseHint: String,
    val storageNote: String,
    val importTitle: String,
    val recoveryPhrase: String,
    val canImport: Boolean,
    val walletStatus: String?,
    val walletError: String?,
    val retryTitle: String,
    val resetTitle: String,
    val confirmRemoval: Boolean,
    val removalTitle: String,
    val removalBody: String,
    val removalConfirmTitle: String,
    val cancelTitle: String
)

class FlintNativeOnboardingController internal constructor(
    private val onboarding: OnboardingViewModel,
    walletAccess: WalletAccess,
    languageChanges: Flow<*>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wallet = WalletViewModel(walletAccess)
    private val snapshot = MutableStateFlow<FlintNativeOnboardingSnapshot?>(null)
    private var resolvingInitialWallet = walletAccess.state.value.isInitialising()
    private var currentStep =
        if (walletAccess.state.value == WalletAccessState.NoWallet) STEP_WELCOME else STEP_WALLET
    private var resetRequested = false

    init {
        scope.launch {
            combine(onboarding.uiState, wallet.state, languageChanges) { _, _, _ -> Unit }
                .collect { publishSnapshot() }
        }
    }

    fun observe(onChange: (FlintNativeOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun continueWelcome() = moveTo(STEP_FEATURES)

    fun setFeaturePage(page: Int) {
        onboarding.setFeaturesPage(page)
    }

    fun continueFeatures() = moveTo(STEP_AUTO_PAY)

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
        moveTo(STEP_AGREEMENT)
    }

    fun setAgreement(agreed: Boolean) {
        onboarding.setAgreement(agreed)
    }

    fun continueAgreement() {
        if (onboarding.uiState.value.hasAgreed) moveTo(STEP_INSTRUCTIONS)
    }

    fun showWalletConnection() = moveTo(STEP_WALLET)

    fun updateRecoveryPhrase(value: String) {
        wallet.dispatch(WalletAction.RecoveryPhraseChanged(value))
    }

    fun importWallet() {
        wallet.dispatch(WalletAction.Import)
    }

    fun retryWallet() {
        wallet.dispatch(WalletAction.Retry)
    }

    fun requestReset() {
        wallet.dispatch(WalletAction.RequestRemoval)
    }

    fun cancelReset() {
        wallet.dispatch(WalletAction.CancelRemoval)
    }

    fun confirmReset() {
        resetRequested = true
        wallet.dispatch(WalletAction.ConfirmRemoval)
    }

    fun back() {
        when (effectiveStep(wallet.state.value.access)) {
            STEP_FEATURES -> moveTo(STEP_WELCOME)

            STEP_AUTO_PAY -> moveTo(STEP_FEATURES)

            STEP_AGREEMENT -> moveTo(STEP_AUTO_PAY)

            STEP_INSTRUCTIONS -> moveTo(STEP_AGREEMENT)

            STEP_WALLET -> {
                if (wallet.state.value.access == WalletAccessState.NoWallet) {
                    moveTo(STEP_INSTRUCTIONS)
                }
            }
        }
    }

    private fun moveTo(step: String) {
        currentStep = step
        scope.launch { publishSnapshot() }
    }

    private suspend fun publishSnapshot() {
        val state = onboarding.uiState.value
        val walletState = wallet.state.value
        if (resolvingInitialWallet && !walletState.access.isInitialising()) {
            resolvingInitialWallet = false
            currentStep =
                if (walletState.access == WalletAccessState.NoWallet) STEP_WELCOME else STEP_WALLET
        }
        if (resetRequested && walletState.access == WalletAccessState.NoWallet) {
            resetRequested = false
            currentStep = STEP_WELCOME
            onboarding.setFeaturesPage(0)
            onboarding.setAgreement(false)
        }

        val common = nativeOnboardingText()
        val flint = nativeFlintOnboardingText()
        val walletText = nativeFlintWalletConnectionText()
        val step = effectiveStep(walletState.access)
        val formatter = currentAmountFormatter()
        val sats = formatter.format(DisplayAmount(state.thresholdSats, DisplayCurrency.Satoshi))
        val thresholdAmount =
            state.thresholdCurrencyEquivalent
                ?.let(formatter::format)
                ?.let { "$sats ($it)" }
                ?: sats
        val recovery = recoveryCopy(walletState.access, walletText)

        snapshot.value =
            FlintNativeOnboardingSnapshot(
                step = step,
                stepIndex = stepIndex(step),
                stepCount = ONBOARDING_STEP_COUNT,
                canGoBack = step != STEP_WELCOME && !isRecovery(walletState.access),
                backTitle = common.back,
                welcomeTitle = flint.welcomeTitle,
                welcomeSubtitle = flint.welcomeSubtitle,
                welcomeDescription = flint.welcomeDescription,
                getStartedTitle = common.getStarted,
                featurePages = flint.featurePages.map(NativeFlintOnboardingPage::toSnapshot),
                featurePage = state.featuresPage,
                featuresNextTitle = common.featuresContinue,
                autoPayTitle = common.autoPayTitle,
                autoPayBody = flint.autoPayBody,
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
                agreementBody = flint.agreementBody,
                agreementCheckboxTitle = common.agreementCheckbox,
                agreementNextTitle = common.agreementContinue,
                hasAgreed = state.hasAgreed,
                instructionsTitle = flint.instructionsTitle,
                instructionsIntro = flint.instructionsIntro,
                instructionSteps = flint.instructionSteps,
                connectWalletTitle = walletText.importAction,
                walletKind = walletKind(walletState.access),
                walletTitle = recovery?.first ?: walletText.importTitle,
                walletBody = recovery?.second ?: walletText.importBody,
                phraseLabel = walletText.phraseLabel,
                phraseHint = walletText.phraseHint,
                storageNote = walletText.storageNote,
                importTitle = walletText.importAction,
                recoveryPhrase = walletState.recoveryPhrase,
                canImport =
                    walletState.access == WalletAccessState.NoWallet &&
                        walletState.recoveryPhrase.isNotBlank(),
                walletStatus = walletStatus(walletState.access, walletText),
                walletError = walletState.message?.message(walletText),
                retryTitle = walletText.retry,
                resetTitle = walletText.resetAction,
                confirmRemoval = walletState.confirmRemoval,
                removalTitle = walletText.removeTitle,
                removalBody = walletText.removeBody,
                removalConfirmTitle = walletText.removeConfirm,
                cancelTitle = walletText.cancel
            )
    }

    private fun effectiveStep(access: WalletAccessState): String =
        if (isRecovery(access)) STEP_WALLET else currentStep

    private fun isRecovery(access: WalletAccessState): Boolean = access.isInitialising() ||
        access == WalletAccessState.ReconnectRequired ||
        access == WalletAccessState.ResetRequired ||
        access is WalletAccessState.CredentialProblem ||
        access == WalletAccessState.Removing

    private fun stepIndex(step: String): Int = when (step) {
        STEP_WELCOME -> 0
        STEP_FEATURES -> 1
        STEP_AUTO_PAY -> 2
        STEP_AGREEMENT -> 3
        else -> 4
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
        const val ONBOARDING_STEP_COUNT = 5
    }
}

private fun WalletAccessState.isInitialising(): Boolean =
    this == WalletAccessState.Loading || this == WalletAccessState.Connecting

private fun NativeFlintOnboardingPage.toSnapshot(): FlintNativeOnboardingPage =
    FlintNativeOnboardingPage(title = title, subtitle = subtitle, body = body)

private fun walletKind(access: WalletAccessState): String = when (access) {
    WalletAccessState.NoWallet -> "import"

    WalletAccessState.Loading,
    WalletAccessState.Connecting,
    WalletAccessState.Removing -> "progress"

    WalletAccessState.Connected -> "connected"

    else -> "recovery"
}

private fun walletStatus(
    access: WalletAccessState,
    text: xyz.lilsus.flint.feature.walletconnection.NativeFlintWalletConnectionText
): String? = when (access) {
    WalletAccessState.Loading -> text.loading
    WalletAccessState.Connecting -> text.connecting
    WalletAccessState.Removing -> text.removing
    WalletAccessState.Connected -> null
    else -> null
}

private fun recoveryCopy(
    access: WalletAccessState,
    text: xyz.lilsus.flint.feature.walletconnection.NativeFlintWalletConnectionText
): Pair<String, String>? = when (access) {
    WalletAccessState.ReconnectRequired -> text.reconnectTitle to text.reconnectBody

    WalletAccessState.ResetRequired -> text.resetTitle to text.resetBody

    is WalletAccessState.CredentialProblem ->
        text.credentialTitle to
            when (access.kind) {
                CredentialProblemKind.UNAVAILABLE -> text.credentialUnavailable
                CredentialProblemKind.INVALIDATED -> text.credentialInvalidated
                CredentialProblemKind.CORRUPT -> text.credentialCorrupt
            }

    else -> null
}

private fun WalletMessage.message(
    text: xyz.lilsus.flint.feature.walletconnection.NativeFlintWalletConnectionText
): String = when (this) {
    WalletMessage.ALREADY_CONFIGURED -> text.errorAlreadyConfigured
    WalletMessage.INVALID_MNEMONIC -> text.errorInvalidMnemonic
    WalletMessage.CONNECTION_FAILED -> text.errorConnection
    WalletMessage.CREDENTIAL_STORE_FAILED -> text.errorStorage
    WalletMessage.RESET_REQUIRED -> text.errorReset
}
