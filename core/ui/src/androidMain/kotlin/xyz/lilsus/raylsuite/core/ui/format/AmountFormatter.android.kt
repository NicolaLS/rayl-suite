package xyz.lilsus.raylsuite.core.ui.format

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

internal actual fun createAmountFormatter(locale: AppLocale): AmountFormatter =
    AndroidAmountFormatter(locale)

private class AndroidAmountFormatter(locale: AppLocale) : AmountFormatter {
    private val javaLocale = locale.toLocale()
    private val fiatFormatters = mutableMapOf<String, NumberFormat>()
    private val satoshiFormatter by lazy {
        NumberFormat.getIntegerInstance(javaLocale)
    }

    override fun format(amount: DisplayAmount): String = when (val currency = amount.currency) {
        is DisplayCurrency.Fiat -> formatFiat(amount.minor, currency)
        DisplayCurrency.Satoshi -> formatSatoshi(amount.minor)
    }

    private fun formatFiat(minor: Long, currency: DisplayCurrency.Fiat): String {
        val code = currency.iso4217.uppercase(Locale.ROOT)
        val formatter =
            fiatFormatters.getOrPut(code) {
                NumberFormat.getCurrencyInstance(javaLocale).apply {
                    val javaCurrency = Currency.getInstance(code)
                    this.currency = javaCurrency
                    val fractionDigits =
                        javaCurrency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
                    minimumFractionDigits = fractionDigits
                    maximumFractionDigits = fractionDigits
                }
            }
        val fractionDigits = formatter.currency?.defaultFractionDigits ?: 2
        val major = BigDecimal.valueOf(minor, fractionDigits)
        return formatter.format(major)
    }

    private fun formatSatoshi(minor: Long): String = "${satoshiFormatter.format(minor)} sat"
}

private fun AppLocale.toLocale(): Locale {
    val locale = Locale.forLanguageTag(languageTag)
    return if (locale.language.isEmpty()) Locale.getDefault() else locale
}
