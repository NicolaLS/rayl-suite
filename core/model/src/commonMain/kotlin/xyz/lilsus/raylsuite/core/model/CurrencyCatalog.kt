package xyz.lilsus.raylsuite.core.model

data class CurrencyInfo(val code: String, val currency: DisplayCurrency, val fractionDigits: Int)

object CurrencyCatalog {
    private val entries =
        listOf(
            CurrencyInfo(
                code = "SAT",
                currency = DisplayCurrency.Satoshi,
                fractionDigits = 0
            ),
            fiatCurrency("USD"),
            fiatCurrency("EUR"),
            fiatCurrency("GBP"),
            fiatCurrency("CAD"),
            fiatCurrency("AUD"),
            fiatCurrency("CHF"),
            fiatCurrency(
                code = "JPY",
                fractionDigits = 0
            )
        )

    private val byCode = entries.associateBy { it.code }

    val supported: List<CurrencyInfo> = entries

    val supportedCodes: List<String> = entries.map(CurrencyInfo::code)

    fun infoFor(code: String): CurrencyInfo = byCode[code.uppercase()]
        ?: byCode.getValue(DEFAULT_CODE)

    fun infoFor(currency: DisplayCurrency): CurrencyInfo = when (currency) {
        DisplayCurrency.Satoshi -> infoFor("SAT")
        is DisplayCurrency.Fiat -> infoFor(currency.iso4217)
    }

    const val DEFAULT_CODE = "SAT"

    private fun fiatCurrency(code: String, fractionDigits: Int = 2) = CurrencyInfo(
        code = code,
        currency = DisplayCurrency.Fiat(code),
        fractionDigits = fractionDigits
    )
}
