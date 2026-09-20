package xyz.lilsus.rayl

import xyz.lilsus.blip.BlinkExperienceConfiguration
import xyz.lilsus.flint.SparkExperienceConfiguration
import xyz.lilsus.lasr.NwcExperienceConfiguration
import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

internal const val RAYL_PREFERENCES = "rayl_preferences"
internal val RAYL_LEGAL_LINKS = SettingsLegalLinks(
    privacyPolicyUrl =
        "https://github.com/NicolaLS/rayl-suite/blob/main/docs/legal/privacy.md",
    termsUrl = "https://github.com/NicolaLS/rayl-suite/blob/main/docs/legal/terms.md",
    sourceCodeUrl = "https://github.com/NicolaLS/rayl-suite"
)
internal val RAYL_BLINK = BlinkExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    walletPreferencesName = "rayl_blink_connection",
    credentialsName = "rayl_blink_credentials",
    legalLinks = RAYL_LEGAL_LINKS,
    welcomeCompleted = true
)
internal val RAYL_NWC = NwcExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    walletPreferencesName = "rayl_nwc_connection",
    credentialsName = "rayl_nwc_credentials",
    legalLinks = RAYL_LEGAL_LINKS,
    welcomeCompleted = true
)
internal val RAYL_SPARK = SparkExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    legalLinks = RAYL_LEGAL_LINKS,
    welcomeCompleted = true
)
