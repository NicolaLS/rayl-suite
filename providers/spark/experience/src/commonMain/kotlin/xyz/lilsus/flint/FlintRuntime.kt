package xyz.lilsus.flint

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.lilsus.flint.feature.payment.PaymentCoordinator
import xyz.lilsus.flint.feature.payment.PaymentUiState
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.integration.exchangerate.CoinGeckoBitcoinPriceProvider

/**
 * Spark experience objects, held outside composition so both the Compose host and the native
 * iOS shell can share one instance.
 */
internal class FlintRuntime(
    host: FlintAppHost,
    val configuration: SparkExperienceConfiguration,
    appSettings: Settings,
    haptics: HapticFeedbackManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val networkLabel = host.bootstrapConfig.environment.networkLabel
    val walletAccess = host.walletAccess

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val bitcoinPriceProvider = CoinGeckoBitcoinPriceProvider()
    val tabState = AppTabState()
    val paymentHub = PaymentHubController(repository = paymentHubRepository, scope = scope)

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
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    fun resetPaymentSession() {
        paymentCoordinator.resetSession()
    }

    fun clear() {
        paymentCoordinator.clear()
        onboardingViewModel.clear()
        languageRepository.close()
        scope.cancel()
    }
}
