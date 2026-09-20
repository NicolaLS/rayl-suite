package xyz.lilsus.flint

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and app preferences, without provider policy. */
data class SparkExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val legalLinks: SettingsLegalLinks
)
