package xyz.lilsus.rayl.providers.spark.application

enum class AppEnvironment {
    DEBUG,
    PRODUCTION
    ;

    val networkLabel: String
        get() = when (this) {
            DEBUG -> "REGTEST"
            PRODUCTION -> "MAINNET"
        }
}

class AppBootstrapConfig(val environment: AppEnvironment, breezApiKey: String? = null) {
    private val apiKey = breezApiKey?.trim()?.takeIf(String::isNotEmpty)

    fun sdkApiKey(): String? = when (environment) {
        AppEnvironment.DEBUG -> null

        AppEnvironment.PRODUCTION ->
            apiKey
                ?: throw AppConfigurationException("Breez API key is required for Production")
    }

    override fun toString(): String =
        "AppBootstrapConfig(environment=$environment, breezApiKey=<redacted>)"
}

class AppConfigurationException(message: String) : IllegalStateException(message)
