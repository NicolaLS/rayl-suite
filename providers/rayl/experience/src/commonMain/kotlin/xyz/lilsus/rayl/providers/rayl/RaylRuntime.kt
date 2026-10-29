package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.feature.payment.RaylPaymentSettlement
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketTimings
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPocket
import xyz.lilsus.raylsuite.core.network.createNetworkConnectivity
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentFlowCoordinator
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiState
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.lnurl.KtorLnurlPayClient

internal enum class RaylExit { None, Working, Failed, Done }

/**
 * Rayl experience objects, held outside composition so both the Compose host and the native iOS
 * shell share one instance. There is no setup step: the pocket starts as soon as Rayl mounts.
 */
internal class RaylRuntime(
    appSettings: Settings,
    val configuration: RaylExperienceConfiguration,
    environment: RaylEnvironment,
    val pocket: RaylPocket,
    haptics: HapticFeedbackManager,
    onFundingInvoice: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkConnectivity by lazy { createNetworkConnectivity() }
    private var closed = false
    private var exitErases = false

    val canErase = environment == RaylEnvironment.DEBUG

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val tabState = AppTabState()
    val paymentHub = PaymentHubController(paymentHubRepository, scope)
    val bitcoinPriceProvider = RaylBitcoinPriceProvider()
    private val lnurlPayClient = KtorLnurlPayClient(networkConnectivity)
    val paymentCoordinator = PaymentFlowCoordinator(
        settlement = RaylPaymentSettlement(pocket),
        lnurlPayClient = lnurlPayClient,
        bitcoinPriceProvider = bitcoinPriceProvider,
        currencyPreferences = currencyPreferences,
        paymentPreferences = paymentPreferences,
        paymentHub = paymentHub,
        haptics = haptics
    )
    val funding = RaylFunding(pocket, configuration.appName, scope, onFundingInvoice)

    private val mutableExit = MutableStateFlow(RaylExit.None)
    val exit: StateFlow<RaylExit> = mutableExit.asStateFlow()

    init {
        PocketTimings.mark("experience created")
        pocket.start()
        scope.launch {
            // A link that arrives while the pocket connects waits in the settlement step.
            RaylPaymentLinks.events.collect { uri ->
                PocketTimings.mark("payment link received")
                if (mutableExit.value == RaylExit.None && !paymentCoordinator.isSubmitting.value) {
                    tabState.requestScan()
                    paymentCoordinator.dispatch(PaymentIntent.DeepLinkReceived(uri))
                }
            }
        }
        scope.launch {
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    fun retryPocket() {
        if (mutableExit.value == RaylExit.None) pocket.start()
    }

    /**
     * Returns to the wallet chooser. The pocket only closes; its key and funds stay on this device
     * for the next time Rayl is chosen. [erase] deletes them and is limited to debug builds.
     */
    fun leave(erase: Boolean = false) {
        if (closed || paymentCoordinator.isSubmitting.value || (erase && !canErase)) return
        if (mutableExit.value == RaylExit.Working || mutableExit.value == RaylExit.Done) return
        exitErases = erase
        mutableExit.value = RaylExit.Working
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableExit.value = try {
                paymentCoordinator.resetSession()
                funding.reset()
                val finished = if (erase) {
                    pocket.erase()
                } else {
                    pocket.close()
                    true
                }
                if (finished) RaylExit.Done else RaylExit.Failed
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RaylExit.Failed
            }
        }
    }

    fun retryLeave() {
        if (mutableExit.value == RaylExit.Failed) {
            mutableExit.value = RaylExit.None
            leave(exitErases)
        }
    }

    /** Best-effort platform teardown; [leave] awaits the pocket before the host moves on. */
    fun clear() {
        if (closed) return
        closed = true
        scope.cancel()
        paymentCoordinator.clear()
        bitcoinPriceProvider.close()
        lnurlPayClient.close()
        languageRepository.close()
        RaylPaymentLinks.clear()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(
            start = CoroutineStart.UNDISPATCHED
        ) {
            runCatching { pocket.close() }
        }
    }
}
