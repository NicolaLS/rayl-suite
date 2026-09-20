package xyz.lilsus.rayl.providers.blink

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import xyz.lilsus.rayl.providers.blink.feature.onboarding.BlinkOnboardingStep
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository

class BlinkOnboardingStateTest {
    @Test
    fun connectingBeforeInterruptionDoesNotCompleteOnboarding() {
        val settings = MapSettings()
        BlinkOnboardingState(settings, welcomeCompleted = false).moveTo(BlinkOnboardingStep.Connect)

        val restarted = BlinkOnboardingState(settings, welcomeCompleted = false)

        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertEquals(BlinkOnboardingStep.Features, restarted.step.value.destination(walletConnected = true))
        assertFalse(restarted.canEnterApp(walletConnected = true))
    }

    @Test
    fun unfinishedStageResumesAfterRestartAndReconnection() {
        val settings = MapSettings()
        BlinkOnboardingState(settings, welcomeCompleted = true).moveTo(BlinkOnboardingStep.AutoPay)

        val restarted = BlinkOnboardingState(settings, welcomeCompleted = true)

        assertEquals(BlinkOnboardingStep.AutoPay, restarted.step.value.destination(walletConnected = true))
        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertFalse(restarted.canEnterApp(walletConnected = false))
        assertEquals(BlinkOnboardingStep.AutoPay, restarted.step.value.destination(walletConnected = true))
    }

    @Test
    fun completionSurvivesWalletRemovalAndAllowsOnlyConnectedAppEntry() {
        val settings = MapSettings()
        BlinkOnboardingState(settings, welcomeCompleted = true).moveTo(BlinkOnboardingStep.Complete)

        val restarted = BlinkOnboardingState(settings, welcomeCompleted = true)

        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertFalse(restarted.canEnterApp(walletConnected = false))
        assertTrue(restarted.canEnterApp(walletConnected = true))
        assertEquals(BlinkOnboardingStep.Complete, restarted.step.value.destination(walletConnected = true))
    }

    @Test
    fun welcomeIsOnlyNeededUntilFirstConnectionSetupBegins() {
        assertEquals(
            BlinkOnboardingStep.Welcome,
            BlinkOnboardingState(MapSettings(), welcomeCompleted = false).step.value
        )
        assertEquals(
            BlinkOnboardingStep.Connect,
            BlinkOnboardingState(MapSettings(), welcomeCompleted = true).step.value
        )
    }

    @Test
    fun paymentChoicesSurviveAnInterruptedConfigurationStep() {
        val settings = MapSettings()
        fun model() = OnboardingViewModel(
            paymentPreferences = DefaultPaymentPreferencesRepository(settings),
            currencyPreferences = DefaultCurrencyPreferences(settings),
            dispatcher = Dispatchers.Unconfined
        )
        val original = model()
        assertEquals(PaymentConfirmationMode.Above, original.uiState.value.confirmationMode)
        assertEquals(10_000L, original.uiState.value.thresholdSats)
        original.setConfirmationMode(PaymentConfirmationMode.Always)
        original.setThreshold(2_000L)
        original.clear()

        val restarted = model()
        assertEquals(PaymentConfirmationMode.Always, restarted.uiState.value.confirmationMode)
        assertEquals(2_000L, restarted.uiState.value.thresholdSats)
        restarted.clear()
    }
}
