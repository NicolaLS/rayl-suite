package xyz.lilsus.raylsuite.core.model

sealed interface DisplayCurrency {
    data object Satoshi : DisplayCurrency

    data class Fiat(val iso4217: String) : DisplayCurrency
}

data class DisplayAmount(val minor: Long, val currency: DisplayCurrency)

/**
 * A persisted display-currency amount. It is quoted into satoshis at invocation time, so a
 * stored fiat amount is a preset, not a guaranteed fiat transfer.
 */
data class StoredAmount(val minor: Long, val currencyCode: String) {
    val normalizedCurrencyCode: String
        get() = currencyCode.trim().uppercase()
}
