package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.clearIOSWalletStorage
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.createIOSWalletAccess
import xyz.lilsus.raylsuite.core.model.WalletStartMode

fun createIOSAppHost(
    environment: SparkEnvironment,
    startMode: WalletStartMode,
    breezApiKey: String? = null
): SparkAppHost {
    val bootstrapConfig = AppBootstrapConfig(environment.toAppEnvironment(), breezApiKey)
    return SparkAppHost(
        bootstrapConfig = bootstrapConfig,
        startMode = startMode,
        walletAccess = createIOSWalletAccess(bootstrapConfig, startMode)
    )
}

suspend fun clearSavedSparkWallet(): Boolean = clearIOSWalletStorage()
