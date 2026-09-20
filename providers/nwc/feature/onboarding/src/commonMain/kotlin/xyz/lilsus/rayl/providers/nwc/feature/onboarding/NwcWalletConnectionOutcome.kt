package xyz.lilsus.rayl.providers.nwc.feature.onboarding

enum class NwcWalletConnectionOutcome {
    ResumeOnboarding,
    CompleteOnboarding,
    FinishSettings
}

fun nwcWalletConnectionOutcome(
    fromSettings: Boolean,
    hasAgreed: Boolean
): NwcWalletConnectionOutcome = when {
    fromSettings -> NwcWalletConnectionOutcome.FinishSettings
    hasAgreed -> NwcWalletConnectionOutcome.CompleteOnboarding
    else -> NwcWalletConnectionOutcome.ResumeOnboarding
}
