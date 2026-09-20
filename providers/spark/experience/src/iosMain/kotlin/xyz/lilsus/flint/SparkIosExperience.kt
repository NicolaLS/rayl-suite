package xyz.lilsus.flint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.feature.payment.PaymentEvent
import xyz.lilsus.flint.feature.payment.toNativePaymentScreenState
import xyz.lilsus.flint.feature.payment.toNativeRecentItem
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.createHapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.appTabTitles
import xyz.lilsus.raylsuite.feature.appshell.nativeColorSchemeValue
import xyz.lilsus.raylsuite.feature.paymenthub.NativePaymentHubController
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentController
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentScanController
import xyz.lilsus.raylsuite.feature.paymentui.localizedMessage
import xyz.lilsus.raylsuite.feature.settings.NativeSettingsController
import xyz.lilsus.raylsuite.feature.settings.nativeSettingsAppVersionName

/**
 * The Kotlin side of Spark's native iOS shell. Swift owns the `TabView`; this owns the app scope
 * so every tab's view controller shares one runtime.
 */
class SparkIosExperience(
    host: FlintAppHost,
    private val configuration: SparkExperienceConfiguration
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val runtime =
        FlintRuntime(
            host = host,
            configuration = configuration,
            appSettings = createAppSettings(),
            haptics = createHapticFeedbackManager()
        )

    private val nativeSettingsController =
        NativeSettingsController(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            legalLinks = configuration.legalLinks,
            appVersionName = nativeSettingsAppVersionName()
        )

    private val nativeScanController by lazy(::createNativeScanController)

    private val nativeRecentController by lazy(::createNativeRecentController)

    private val nativeHubController by lazy {
        NativePaymentHubController(
            repository = runtime.paymentHubRepository,
            host = runtime.paymentHub,
            languageChanges = runtime.languageRepository.preference,
            currencyCodes = runtime.currencyPreferences.code
        )
    }

    private val nativeOnboardingController by lazy {
        FlintNativeOnboardingController(
            onboarding = runtime.onboardingViewModel,
            walletAccess = runtime.walletAccess,
            languageChanges = runtime.languageRepository.preference
        )
    }

    private val nativeWalletSettingsController by lazy {
        FlintNativeWalletSettingsController(
            walletAccess = runtime.walletAccess,
            networkLabel = runtime.networkLabel,
            languageChanges = runtime.languageRepository.preference,
            isSubmitting = runtime.paymentCoordinator.isSubmitting
        )
    }

    /** One of `loading`, `onboarding`, or `tabs`. */
    fun stage(): String = runtime.walletAccess.state.value.toStage()

    fun observeStage(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.walletAccess.state.collect { onChange(it.toStage()) }
            }
        return { job.cancel() }
    }

    fun selectedTab(): String = runtime.tabState.selectedTab.value.storedValue

    fun observeSelectedTab(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.tabState.selectedTab.collect { onChange(it.storedValue) }
            }
        return { job.cancel() }
    }

    fun observeTheme(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.themePreferences.preference.collect {
                    onChange(it.nativeColorSchemeValue())
                }
            }
        return { job.cancel() }
    }

    fun recentBadgeCount(): Int = runtime.paymentCoordinator.newSessionTransactionCount.value

    fun observeRecentBadgeCount(onChange: (Int) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.paymentCoordinator.newSessionTransactionCount.collect(onChange)
            }
        return { job.cancel() }
    }

    /** Localized tab titles keyed by tab ID, so the native tab bar uses one set of strings. */
    fun observeTabTitles(onChange: (Map<String, String>) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.languageRepository.preference.collect { onChange(appTabTitles()) }
            }
        return { job.cancel() }
    }

    fun selectTab(tab: String) {
        if (!runtime.paymentCoordinator.isSubmitting.value) {
            runtime.tabState.select(AppTab.fromStoredValue(tab))
        }
    }

    fun settingsController(): NativeSettingsController = nativeSettingsController

    fun scanController(): NativePaymentScanController = nativeScanController

    fun recentController(): NativePaymentRecentController = nativeRecentController

    fun hubController(): NativePaymentHubController = nativeHubController

    fun onboardingController(): FlintNativeOnboardingController = nativeOnboardingController

    fun walletSettingsController(): FlintNativeWalletSettingsController =
        nativeWalletSettingsController

    private fun createNativeScanController(): NativePaymentScanController {
        val controller =
            NativePaymentScanController(
                onPaymentIntent = runtime.paymentCoordinator::dispatch,
                onHubIntent = runtime.paymentHub::dispatch
            )
        observerScope.launch {
            combine(
                runtime.paymentCoordinator.uiState,
                runtime.paymentHub.state,
                runtime.languageRepository.preference
            ) { payment, hubState, _ ->
                controller.update(
                    payment = payment.toNativePaymentScreenState(),
                    appTitle = configuration.appName,
                    savePrompt = hubState.savePrompt
                )
            }.collect {}
        }
        observerScope.launch {
            runtime.paymentCoordinator.events.collect { event ->
                val message =
                    when (event) {
                        is PaymentEvent.ShowError ->
                            xyz.lilsus.flint.feature.payment.getFlintPaymentErrorMessageFor(
                                event.error
                            )

                        is PaymentEvent.ShowToast -> event.message.localizedMessage()
                    }
                controller.emitMessage(message)
            }
        }
        return controller
    }

    private fun createNativeRecentController(): NativePaymentRecentController {
        val controller =
            NativePaymentRecentController(
                onIntent = runtime.paymentCoordinator::dispatch,
                onSelectTransaction = runtime.tabState::selectTransaction
            )
        observerScope.launch {
            combine(
                runtime.paymentCoordinator.sessionTransactions,
                runtime.tabState.selectedTransactionId,
                runtime.languageRepository.preference
            ) { transactions, selectedId, _ ->
                controller.update(
                    items = transactions.map { it.toNativeRecentItem() },
                    selectedTransactionId = selectedId,
                    receiptFeeInformation = null
                )
            }.collect {}
        }
        return controller
    }
}

private fun WalletAccessState.toStage(): String = when (this) {
    WalletAccessState.Connected -> "tabs"
    WalletAccessState.Loading -> "loading"
    else -> "onboarding"
}
