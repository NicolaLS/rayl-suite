package xyz.lilsus.flint

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

internal const val FLINT_PREFERENCES = "flint_preferences"

internal val FLINT_LEGAL_LINKS =
    SettingsLegalLinks(
        privacyPolicyUrl =
            "https://github.com/NicolaLS/rayl-suite/blob/main/docs/legal/flint/privacy.md",
        sourceCodeUrl = "https://github.com/NicolaLS/rayl-suite"
    )

internal val FLINT_EXPERIENCE = SparkExperienceConfiguration(
    appName = "Flint",
    preferencesName = FLINT_PREFERENCES,
    legalLinks = FLINT_LEGAL_LINKS
)
