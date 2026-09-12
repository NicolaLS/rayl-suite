package xyz.lilsus.raylsuite.feature.paymentsettings

import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode

class PaymentConfirmationPolicy(private val preferencesRepository: PaymentPreferencesRepository) {
    suspend fun shouldConfirm(
        amountMsats: Long,
        isManualEntry: Boolean,
        isPresetTarget: Boolean = false
    ): Boolean {
        require(amountMsats >= 0) { "amountMsats must be non-negative" }

        // Submitting an entered amount is the manual flow's authorization.
        if (isManualEntry) return false
        if (isPresetTarget) return true
        val preferences = preferencesRepository.current()

        val amountSats = amountMsats / MSATS_PER_SAT
        return when (preferences.confirmationMode) {
            PaymentConfirmationMode.Always -> true
            PaymentConfirmationMode.Above -> amountSats >= preferences.thresholdSats
        }
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
