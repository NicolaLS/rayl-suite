package xyz.lilsus.rayl.providers.nwc.integration.nwc

import xyz.lilsus.raylsuite.core.settings.SecureStringStore

internal class TestSecureStringStore : SecureStringStore {
    private val values = mutableMapOf<String, String>()

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getStringOrNull(key: String): String? = values[key]

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() {
        values.clear()
    }
}
