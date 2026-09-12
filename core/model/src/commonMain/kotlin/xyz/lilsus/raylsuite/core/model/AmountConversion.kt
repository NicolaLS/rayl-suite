package xyz.lilsus.raylsuite.core.model

import kotlin.math.pow
import kotlin.math.roundToLong

fun convertMsatsToDisplayAmount(
    msats: Long,
    info: CurrencyInfo,
    fiatPricePerBitcoin: Double?
): DisplayAmount? = when (val currency = info.currency) {
    DisplayCurrency.Satoshi ->
        DisplayAmount(
            minor = msats / MSATS_PER_SAT,
            currency = currency
        )

    is DisplayCurrency.Fiat -> {
        val rate = fiatPricePerBitcoin ?: return null
        val bitcoin = msats.toDouble() / MSATS_PER_BITCOIN
        val minorUnitFactor = 10.0.pow(info.fractionDigits)
        val convertedMinor = (bitcoin * rate * minorUnitFactor).roundToLong()
        val nonZeroMinor =
            if (convertedMinor <= 0 && msats > 0) {
                1
            } else {
                convertedMinor
            }

        DisplayAmount(
            minor = nonZeroMinor,
            currency = currency
        )
    }
}

private const val MSATS_PER_SAT = 1_000L
private const val MSATS_PER_BITCOIN = 100_000_000_000L
