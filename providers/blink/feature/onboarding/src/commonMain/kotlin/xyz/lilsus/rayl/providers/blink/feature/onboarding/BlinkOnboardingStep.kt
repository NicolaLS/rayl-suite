package xyz.lilsus.rayl.providers.blink.feature.onboarding

enum class BlinkOnboardingStep {
    Connect,
    Features,
    AutoPay,
    Agreement,
    Complete;

    fun destination(walletConnected: Boolean): BlinkOnboardingStep = when {
        !walletConnected -> Connect
        this == Connect -> Features
        else -> this
    }
}
