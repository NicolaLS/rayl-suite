package xyz.lilsus.rayl.providers.blink

import kotlinx.serialization.Serializable

internal sealed interface BlinkDestination {
    /** The tab shell. Everything below it is a tab-internal destination. */
    @Serializable
    data object Home : BlinkDestination
}
