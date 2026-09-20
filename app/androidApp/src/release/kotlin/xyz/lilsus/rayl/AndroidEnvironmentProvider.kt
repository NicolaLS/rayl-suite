package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.spark.SparkEnvironment

internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.PRODUCTION,
    breezApiKey = BuildConfig.BREEZ_API_KEY
)
