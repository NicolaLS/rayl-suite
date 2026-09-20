package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.createIOSWalletAccess

fun createIOSAppHost(environment: SparkEnvironment, breezApiKey: String? = null): SparkAppHost {
    val bootstrapConfig = AppBootstrapConfig(environment.toAppEnvironment(), breezApiKey)
    return SparkAppHost(
        bootstrapConfig = bootstrapConfig,
        walletAccess = createIOSWalletAccess(bootstrapConfig)
    )
}
