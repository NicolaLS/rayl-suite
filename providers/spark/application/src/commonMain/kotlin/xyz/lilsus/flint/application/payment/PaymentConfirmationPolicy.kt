package xyz.lilsus.flint.application.payment

import xyz.lilsus.raylsuite.core.model.Satoshi

enum class PaymentConfirmationMode {
    ALWAYS,
    THRESHOLD
}

data class PaymentConfirmationPolicy(
    val mode: PaymentConfirmationMode,
    val amountThresholdSats: Satoshi,
    val feeThresholdSats: Satoshi
) {
    fun requiresConfirmation(
        amountSats: Satoshi,
        feeSats: Satoshi,
        origin: PaymentOrigin,
        amountEnteredByUser: Boolean
    ): Boolean = mode == PaymentConfirmationMode.ALWAYS ||
        origin == PaymentOrigin.DEEP_LINK ||
        origin == PaymentOrigin.MANUAL_RECOVERY ||
        amountEnteredByUser ||
        amountSats.value > amountThresholdSats.value ||
        feeSats.value > feeThresholdSats.value

    companion object {
        val Default = PaymentConfirmationPolicy(
            mode = PaymentConfirmationMode.ALWAYS,
            amountThresholdSats = Satoshi.positive(10_000),
            feeThresholdSats = Satoshi.nonNegative(0)
        )
    }
}
