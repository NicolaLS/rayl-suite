package xyz.lilsus.rayl.providers.nwc

import android.content.Context
import xyz.lilsus.raylsuite.core.settings.ConnectionStorageReset
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.createSecureSettings

/** Erases an unmounted provider without creating a wallet or network client. */
fun clearSavedNwcWallet(context: Context, configuration: NwcExperienceConfiguration) {
    ConnectionStorageReset(
        appSettings = createAppSettings(context, configuration.preferencesName),
        connectionSettings = createAppSettings(context, configuration.walletPreferencesName),
        credentials = createSecureSettings(context, configuration.credentialsName),
        marker = "${configuration.walletPreferencesName}.removal.pending"
    ).apply {
        begin()
        finish()
    }
}
