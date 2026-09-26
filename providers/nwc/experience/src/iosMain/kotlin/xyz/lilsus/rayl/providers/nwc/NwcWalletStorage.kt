package xyz.lilsus.rayl.providers.nwc

import xyz.lilsus.raylsuite.core.settings.ConnectionStorageReset
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.createConnectionSettings
import xyz.lilsus.raylsuite.core.settings.createSecureSettings

/** Erases an unmounted provider without creating a wallet or network client. */
fun clearSavedNwcWallet(configuration: NwcExperienceConfiguration) {
    ConnectionStorageReset(
        appSettings = createAppSettings(),
        connectionSettings = createConnectionSettings(configuration.walletPreferencesName),
        credentials = createSecureSettings(configuration.credentialsName),
        marker = "${configuration.walletPreferencesName}.removal.pending"
    ).apply {
        begin()
        finish()
    }
}
