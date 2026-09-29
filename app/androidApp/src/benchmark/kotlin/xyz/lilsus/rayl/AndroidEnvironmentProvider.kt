package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.raylsuite.core.raylapi.RaylBackend

internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    backend = RaylBackend.Staging,
    environment = SparkEnvironment.DEBUG
)
