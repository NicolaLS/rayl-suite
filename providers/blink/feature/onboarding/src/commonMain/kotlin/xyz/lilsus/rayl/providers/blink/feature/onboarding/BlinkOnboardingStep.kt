package xyz.lilsus.rayl.providers.blink.feature.onboarding

enum class BlinkOnboardingStep {
    Welcome,
    Connect,
    Features,
    AutoPay,
    Agreement,
    Complete;

    fun destination(walletConnected: Boolean): BlinkOnboardingStep = when {
        !walletConnected && this == Welcome -> Welcome
        !walletConnected -> Connect
        this == Welcome || this == Connect -> Features
        else -> this
    }
}
