package xyz.lilsus.rayl.providers.blink.feature.payment

import com.russhwolf.settings.Settings

class BlinkPaymentPreferences(private val settings: Settings) {
    init {
        if (!settings.hasKey(KEY_CONFIRM_FEE)) {
            settings.putBoolean(KEY_CONFIRM_FEE, DEFAULT_CONFIRM_FEE)
        }
    }

    fun confirmFee(): Boolean = settings.getBoolean(KEY_CONFIRM_FEE, DEFAULT_CONFIRM_FEE)

    fun setConfirmFee(enabled: Boolean) {
        settings.putBoolean(KEY_CONFIRM_FEE, enabled)
    }

    private companion object {
        const val KEY_CONFIRM_FEE = "blink.payments.confirmFee"
        const val DEFAULT_CONFIRM_FEE = false
    }
}
