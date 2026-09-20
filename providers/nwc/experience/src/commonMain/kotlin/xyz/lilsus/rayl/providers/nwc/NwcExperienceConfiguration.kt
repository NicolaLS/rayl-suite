package xyz.lilsus.rayl.providers.nwc

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and installation-local storage, without provider policy. */
data class NwcExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val walletPreferencesName: String,
    val credentialsName: String,
    val legalLinks: SettingsLegalLinks,
    val welcomeCompleted: Boolean = false
)
