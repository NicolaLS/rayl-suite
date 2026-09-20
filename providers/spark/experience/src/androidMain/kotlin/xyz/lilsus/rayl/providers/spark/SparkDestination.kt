package xyz.lilsus.rayl.providers.spark

import kotlinx.serialization.Serializable

internal sealed interface SparkDestination {
    /** The tab shell. Everything below it is a tab-internal destination. */
    @Serializable
    data object Home : SparkDestination
}
