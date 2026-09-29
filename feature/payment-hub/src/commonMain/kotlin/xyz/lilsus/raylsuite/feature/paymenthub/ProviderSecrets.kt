package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.settings.SecureStringStore

/**
 * Secret widget inputs, such as a provider API key, kept in platform protected storage and never
 * in the Hub document. Keyed by definition URL so each saved provider keeps its own value.
 */
object ProviderSecrets {
    private val store: SecureStringStore? by lazy { platformWidgetSecretStore() }

    /** False where secrets cannot be stored; the user must re-enter them. */
    val persistent: Boolean get() = store != null

    private val memory = mutableMapOf<String, String>()

    fun put(definitionUrl: String, inputId: String, value: String) {
        val key = key(definitionUrl, inputId)
        store?.putString(key, value) ?: memory.put(key, value)
    }

    fun get(definitionUrl: String, inputId: String): String? {
        val key = key(definitionUrl, inputId)
        return store?.getStringOrNull(key) ?: memory[key]
    }

    /** Non-secret inputs from the setup plus the secrets stored for it. */
    fun inputsFor(setup: ProviderWidgetSetup): Map<String, String> {
        val definition = setup.definition ?: return setup.inputs
        return setup.inputs + definition.inputs.filter { it.secret }.mapNotNull { input ->
            get(setup.definitionUrl, input.id)?.let { input.id to it }
        }
    }

    private fun key(definitionUrl: String, inputId: String) = "$definitionUrl#$inputId"
}

internal expect fun platformWidgetSecretStore(): SecureStringStore?
