package xyz.lilsus.rayl.providers.rayl.integration.club

import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/**
 * Where a Rayl build finds Rayl Club: the backend, and the Supabase project that signs members
 * in. Regtest and Mainnet builds use separate projects and keep separate sessions.
 */
data class ClubConfig(
    val backendUrl: String,
    val supabaseUrl: String,
    /** Public by design: it ships in the app and only identifies the Supabase project. */
    val supabasePublishableKey: String,
    /** Secure storage for the session, distinct per network. */
    val storageName: String,
    /**
     * The Google Cloud "Web application" OAuth client that Android's Google sign-in requests ID
     * tokens for; Google sign-in is hidden while it is null.
     */
    val googleWebClientId: String? = GOOGLE_WEB_CLIENT_ID
) {
    companion object {
        /** Google Cloud project `rayl-club`, client "Rayl Club web"; one for both networks. */
        private val GOOGLE_WEB_CLIENT_ID: String? =
            "634575389340-0cpdqgpob4mg7igs466o7ngrmg72m9u5.apps.googleusercontent.com"

        val Regtest = ClubConfig(
            backendUrl = RaylApi.REGTEST_BASE_URL,
            supabaseUrl = "https://mqjrfmajxhjpsijwlmmn.supabase.co",
            supabasePublishableKey = "sb_publishable_VeJvtRgQAfwYAy5vLJnV5A_1EIimMkU",
            storageName = "rayl-club-regtest"
        )

        val Mainnet = ClubConfig(
            backendUrl = RaylApi.BASE_URL,
            supabaseUrl = "https://gqfgyvtavfsjxhkphjlh.supabase.co",
            supabasePublishableKey = "sb_publishable_Q3F1Cw5rewxw0dhwobfycQ_YRzB3_qt",
            storageName = "rayl-club-mainnet"
        )
    }
}
