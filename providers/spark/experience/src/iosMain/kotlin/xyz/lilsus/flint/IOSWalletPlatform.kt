package xyz.lilsus.flint

import xyz.lilsus.flint.application.AppBootstrapConfig
import xyz.lilsus.flint.integration.wallet.platform.createIOSWalletAccess

fun createIOSAppHost(environment: FlintEnvironment, breezApiKey: String? = null): FlintAppHost {
    val bootstrapConfig = AppBootstrapConfig(environment.toAppEnvironment(), breezApiKey)
    return FlintAppHost(
        bootstrapConfig = bootstrapConfig,
        walletAccess = createIOSWalletAccess(bootstrapConfig)
    )
}
