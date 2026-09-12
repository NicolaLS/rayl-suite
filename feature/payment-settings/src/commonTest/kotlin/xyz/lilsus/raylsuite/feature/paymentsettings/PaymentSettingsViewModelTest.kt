@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences

class PaymentSettingsViewModelTest {
    @Test
    fun updatesSharedPreferencesAndThresholdPreview() = runTest {
        val paymentPreferences = DefaultPaymentPreferencesRepository(MapSettings())
        val currencyPreferences = DefaultCurrencyPreferences(MapSettings())
        currencyPreferences.setCode("USD")
        val viewModel =
            PaymentSettingsViewModel(
                paymentPreferences = paymentPreferences,
                currencyPreferences = currencyPreferences,
                bitcoinPriceProvider = { 60_000.0 },
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        advanceUntilIdle()

        assertEquals(
            DisplayAmount(600L, DisplayCurrency.Fiat("USD")),
            viewModel.uiState.value.thresholdCurrencyEquivalent
        )

        viewModel.updateConfirmationThreshold(20_000L)
        viewModel.setOfferToSaveNewTargets(false)
        advanceUntilIdle()

        assertEquals(20_000L, paymentPreferences.current().thresholdSats)
        assertEquals(false, viewModel.uiState.value.offerToSaveNewTargets)
        assertEquals(
            DisplayAmount(1_200L, DisplayCurrency.Fiat("USD")),
            viewModel.uiState.value.thresholdCurrencyEquivalent
        )

        viewModel.clear()
    }
}
