package xyz.lilsus.raylsuite.core.ui.format

import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

/**
 * Where a locale writes a currency's mark around a figure: `$` before in en-US, ` €` after in
 * de-DE. Either side may be empty.
 */
data class CurrencyAffix(val prefix: String, val suffix: String)

interface AmountFormatter {
    fun format(amount: DisplayAmount): String

    /**
     * Currency marks on their own, for a figure the user is still typing. [format] cannot be used
     * there because the entered digits are not yet a number this could round or group.
     */
    fun affix(currency: DisplayCurrency): CurrencyAffix
}

internal expect fun createAmountFormatter(locale: AppLocale): AmountFormatter

/** Creates an amount formatter for presentation code that is not running in composition. */
fun currentAmountFormatter(locale: AppLocale = currentAppLocale()): AmountFormatter =
    createAmountFormatter(locale)

internal const val SATOSHI_SUFFIX = " sat"
