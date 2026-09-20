package xyz.lilsus.rayl.providers.nwc.feature.onboarding

/** The NWC URI captured from a deep link until the connection flow can confirm it. */
class NwcConnectionDraft {
    var uri: String? = null
        private set

    fun set(uri: String) {
        this.uri = uri
    }

    fun clear() {
        uri = null
    }
}
