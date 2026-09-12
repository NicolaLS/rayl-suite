package xyz.lilsus.raylsuite.core.model

enum class PaymentConfirmationMode {
    Always,
    Above
}

data class PaymentPreferences(
    val confirmationMode: PaymentConfirmationMode = PaymentConfirmationMode.Above,
    val thresholdSats: Long = DEFAULT_CONFIRMATION_THRESHOLD_SATS,
    /** Offer to add an unknown Lightning Address to the payment hub after a successful payment. */
    val offerToSaveNewTargets: Boolean = true,
    val vibrateOnScan: Boolean = true,
    val vibrateOnPayment: Boolean = true
) {
    fun normalise(): PaymentPreferences = copy(
        thresholdSats =
            thresholdSats.coerceIn(
                minimumValue = MIN_CONFIRMATION_THRESHOLD_SATS,
                maximumValue = MAX_CONFIRMATION_THRESHOLD_SATS
            )
    )

    companion object {
        const val DEFAULT_CONFIRMATION_THRESHOLD_SATS = 10_000L
        const val MIN_CONFIRMATION_THRESHOLD_SATS = 500L
        const val MAX_CONFIRMATION_THRESHOLD_SATS = 100_000L

        val THRESHOLD_STEPS =
            listOf(
                500L,
                1_000L,
                2_000L,
                5_000L,
                10_000L,
                20_000L,
                50_000L,
                100_000L
            )

        fun thresholdToStepIndex(threshold: Long): Int {
            val index = THRESHOLD_STEPS.indexOfFirst { it >= threshold }
            return if (index < 0) THRESHOLD_STEPS.lastIndex else index
        }
    }
}
