package xyz.lilsus.rayl.providers.spark

import android.content.Context
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.createAndroidWalletAccess

fun createAndroidAppHost(
    context: Context,
    environment: SparkEnvironment,
    breezApiKey: String? = null
): SparkAppHost {
    val bootstrapConfig = AppBootstrapConfig(environment.toAppEnvironment(), breezApiKey)
    return SparkAppHost(
        bootstrapConfig = bootstrapConfig,
        walletAccess = createAndroidWalletAccess(context, bootstrapConfig)
    )
}
