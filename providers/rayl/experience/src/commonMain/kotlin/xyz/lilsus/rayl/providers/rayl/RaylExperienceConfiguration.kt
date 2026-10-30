package xyz.lilsus.rayl.providers.rayl

import xyz.lilsus.rayl.providers.rayl.integration.club.ClubConfig
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketConfig
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketNetwork
import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and app preferences, without provider policy. */
data class RaylExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val legalLinks: SettingsLegalLinks
)

enum class RaylEnvironment {
    /** Regtest pocket; also offers erasing the pocket for development. */
    DEBUG,
    PRODUCTION
}

internal fun RaylEnvironment.pocketConfig(breezApiKey: String?): PocketConfig = PocketConfig(
    network = when (this) {
        RaylEnvironment.DEBUG -> PocketNetwork.Regtest
        RaylEnvironment.PRODUCTION -> PocketNetwork.Mainnet
    },
    breezApiKey = breezApiKey
)

/** Regtest builds use the Club's `regtest` backend and Supabase project; release builds Mainnet's. */
internal fun RaylEnvironment.clubConfig(): ClubConfig = when (this) {
    RaylEnvironment.DEBUG -> ClubConfig.Regtest
    RaylEnvironment.PRODUCTION -> ClubConfig.Mainnet
}
