package xyz.lilsus.rayl.providers.blink

import android.content.Context
import xyz.lilsus.raylsuite.core.settings.ConnectionStorageReset
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.createSecureSettings

/** Erases an unmounted provider without creating a wallet or network client. */
fun clearSavedBlinkWallet(context: Context, configuration: BlinkExperienceConfiguration) {
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
