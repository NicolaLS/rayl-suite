package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.connect
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.wallet.SparkSdkConnector
import xyz.lilsus.rayl.providers.spark.application.wallet.SparkSdkSession
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletCredential

object BreezSparkSdkConnector : SparkSdkConnector {
    override suspend fun connect(
        credential: WalletCredential,
        storageDirectory: String,
        bootstrapConfig: AppBootstrapConfig
    ): SparkSdkSession {
        val sdk = connect(
            ConnectRequest(
                config = bootstrapConfig.sparkConfig(),
                seed = Seed.Mnemonic(credential.value, null),
                storageDir = storageDirectory
            )
        )
        return object : SparkSdkSession {
            override val paymentClient = BreezSparkPaymentClient(sdk)
            override suspend fun disconnect() = sdk.disconnect()
        }
    }
}
