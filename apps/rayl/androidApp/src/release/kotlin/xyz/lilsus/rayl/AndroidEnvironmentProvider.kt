package xyz.lilsus.rayl

import xyz.lilsus.flint.FlintEnvironment

internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = FlintEnvironment.PRODUCTION,
    breezApiKey = BuildConfig.BREEZ_API_KEY
)
