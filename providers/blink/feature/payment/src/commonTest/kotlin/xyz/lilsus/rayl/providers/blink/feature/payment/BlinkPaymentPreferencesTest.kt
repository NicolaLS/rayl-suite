package xyz.lilsus.rayl.providers.blink.feature.payment

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlinkPaymentPreferencesTest {
    @Test
    fun confirmFeeRemainsAnIndependentBlinkPreference() {
        val settings = MapSettings()
        val preferences = BlinkPaymentPreferences(settings)

        assertFalse(preferences.confirmFee())
        preferences.setConfirmFee(true)

        assertTrue(BlinkPaymentPreferences(settings).confirmFee())
    }
}
