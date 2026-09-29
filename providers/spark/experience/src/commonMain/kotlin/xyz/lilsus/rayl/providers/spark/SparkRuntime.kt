package xyz.lilsus.rayl.providers.spark

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.payment.PaymentCoordinator
import xyz.lilsus.rayl.providers.spark.feature.payment.PaymentUiState
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.raylapi.RaylBackend
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.RaylServices
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.widget.RaylServiceClient

/**
 * Spark experience objects, held outside composition so both the Compose host and the native
 * iOS shell can share one instance.
 */
internal class SparkRuntime(
    private val host: SparkAppHost,
    val configuration: SparkExperienceConfiguration,
    appSettings: Settings,
    haptics: HapticFeedbackManager,
    backend: RaylBackend
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var closed = false
    private val mutableCloseFailed = MutableStateFlow(false)
    val closeFailed = mutableCloseFailed.asStateFlow()

    val onboardingState = SparkOnboardingState(appSettings, host.startMode)
    val onboardingCompleted = onboardingState.completed

    val walletAccess = host.walletAccess
    val walletViewModel = WalletViewModel(walletAccess)
    val removed = walletAccess.removed
    val canCancelSetup: Boolean
        get() = !closed && !removed.value && walletAccess.state.value == WalletAccessState.NoWallet

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val bitcoinPriceProvider = RaylBitcoinPriceProvider(baseUrl = backend.baseUrl)
    val tabState = AppTabState()
    val paymentHub = PaymentHubController(
        repository = paymentHubRepository,
        scope = scope,
        services = RaylServices(
            api = RaylServiceClient(backend),
            settings = appSettings,
            scope = scope,
            shareUsage = { paymentPreferences.current().shareUsage }
        )
    )

    val paymentCoordinator =
        PaymentCoordinator(
            engine = walletAccess.payments,
            paymentLinks = host.paymentLinks,
            bitcoinPriceProvider = bitcoinPriceProvider,
            currencyPreferences = currencyPreferences,
            paymentPreferences = paymentPreferences,
            paymentHub = paymentHub,
            haptics = haptics
        )

    val onboardingViewModel =
        OnboardingViewModel(
            paymentPreferences = paymentPreferences,
            currencyPreferences = currencyPreferences,
            bitcoinPriceProvider = bitcoinPriceProvider
        )

    init {
        scope.launch {
            SparkDeepLinks.events.collect { link ->
                if (onboardingState.canHandlePaymentDeepLink(
                        walletConnected = walletAccess.state.value == WalletAccessState.Connected
                    )
                ) {
                    host.paymentLinks.offer(link)
                }
            }
        }
        scope.launch {
            // A widget link opened from Scan continues on the Hub.
            paymentHub.widgetLinks.collect { tabState.select(AppTab.Hub) }
        }
        scope.launch {
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    fun completeOnboarding() {
        onboardingState.complete()
    }

    fun resetPaymentSession() {
        paymentCoordinator.resetSession()
    }

    private fun clearObservers() {
        if (closed) return
        closed = true
        scope.cancel()
        walletViewModel.dispose()
        paymentCoordinator.clear()
        onboardingViewModel.clear()
        languageRepository.close()
        bitcoinPriceProvider.close()
        host.paymentLinks.clear()
        SparkDeepLinks.clear()
    }

    suspend fun close() {
        clearObservers()
        mutableCloseFailed.value = false
        try {
            walletAccess.close()
        } catch (error: Exception) {
            mutableCloseFailed.value = true
            throw error
        }
    }

    /** Best-effort platform teardown; callers that need to handle failure await [close]. */
    fun clear() {
        clearObservers()
        // Composition teardown cannot suspend. Wallet removal already performs its SDK cleanup;
        // cancelling setup awaits close() before leaving.
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main
        ).launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { close() }
        }
    }
}
