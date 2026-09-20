package xyz.lilsus.rayl.providers.nwc

import kotlinx.serialization.Serializable

internal sealed interface NwcDestination {
    /** The tab shell. Everything below it is a tab-internal destination. */
    @Serializable
    data object Home : NwcDestination
}
