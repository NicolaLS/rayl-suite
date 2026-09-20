package xyz.lilsus.flint

import kotlinx.serialization.Serializable

internal sealed interface FlintDestination {
    /** The tab shell. Everything below it is a tab-internal destination. */
    @Serializable
    data object Home : FlintDestination
}
