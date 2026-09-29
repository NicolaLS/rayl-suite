package xyz.lilsus.raylsuite.core.widgetspec

import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.StoredAmount

/**
 * Provider text is plain, single-paragraph text. Control characters and format characters
 * (bidi overrides, zero-width joiners) are refused because they can make one name look like
 * another.
 */
internal fun String.isSafeWidgetText(): Boolean = isNotBlank() &&
    none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }

/** Length in Unicode code points, which is what the spec's limits count. */
internal fun String.codePointLength(): Int = count { !it.isLowSurrogate() }

/**
 * Parses a spec money string. Fiat needs exactly the currency's minor-unit digits (`"12.00"`);
 * `SAT` is a whole number. Only currencies Rayl can convert are accepted.
 */
fun parseWidgetMoney(amount: String, currency: String): StoredAmount? {
    val code = currency.takeIf { it in CurrencyCatalog.supportedCodes } ?: return null
    val digits = CurrencyCatalog.infoFor(code).fractionDigits
    val pattern = if (digits == 0) {
        Regex("""^(0|[1-9][0-9]{0,14})$""")
    } else {
        Regex("""^(0|[1-9][0-9]{0,14})\.([0-9]{$digits})$""")
    }
    val match = pattern.matchEntire(amount) ?: return null
    val whole = match.groupValues[1].toLong()
    val fraction = match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)?.toLong() ?: 0L
    var factor = 1L
    repeat(digits) { factor *= 10 }
    return StoredAmount(whole * factor + fraction, code)
}

/** Formats a parsed amount back into the spec's string form. */
fun StoredAmount.toWidgetMoneyString(): String {
    val digits = CurrencyCatalog.infoFor(normalizedCurrencyCode).fractionDigits
    if (digits == 0) return minor.toString()
    var factor = 1L
    repeat(digits) { factor *= 10 }
    return "${minor / factor}.${(minor % factor).toString().padStart(digits, '0')}"
}
