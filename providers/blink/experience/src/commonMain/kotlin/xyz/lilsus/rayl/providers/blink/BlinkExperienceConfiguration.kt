package xyz.lilsus.rayl.providers.blink

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and installation-local storage, without provider policy. */
data class BlinkExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val walletPreferencesName: String,
    val credentialsName: String,
    val legalLinks: SettingsLegalLinks,
    val welcomeCompleted: Boolean = false
)
