package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and app preferences, without provider policy. */
data class SparkExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val legalLinks: SettingsLegalLinks
)
