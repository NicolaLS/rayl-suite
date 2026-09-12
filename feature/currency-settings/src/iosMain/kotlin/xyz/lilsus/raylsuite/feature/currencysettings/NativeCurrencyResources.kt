package xyz.lilsus.raylsuite.feature.currencysettings

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

suspend fun nativeCurrencyStrings(): Map<String, String> = mapOf(
    "search" to
        nativeString(NativeStringResource(table = "CurrencySettings", key = "search_placeholder")),
    "SAT" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_satoshi")
        ),
    "USD" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_usd")
        ),
    "EUR" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_eur")
        ),
    "GBP" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_gbp")
        ),
    "CAD" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_cad")
        ),
    "AUD" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_aud")
        ),
    "CHF" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_chf")
        ),
    "JPY" to
        nativeString(
            NativeStringResource(table = "CurrencySettings", key = "settings_currency_jpy")
        )
)
