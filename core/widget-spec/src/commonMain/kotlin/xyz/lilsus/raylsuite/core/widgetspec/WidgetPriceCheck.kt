package xyz.lilsus.raylsuite.core.widgetspec

import kotlin.math.abs
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount

enum class WidgetPriceVerdict {
    /** Within tolerance; pay after the usual confirmation. */
    Matches,

    /** More than advertised but plausible; confirm with a visible warning. */
    Higher,

    /** Less than advertised; show the real amount. */
    Lower,

    /** Too far from the advertised amount; never pay. */
    Mismatch,

    /** Rayl has no exchange rate to check a fiat price; never pay. */
    Unverifiable
}

/**
 * The invoice compared with what the provider advertised. [invoiced] is the invoice converted
 * into the advertised currency with Rayl's own rate, when a rate was available.
 */
data class WidgetPriceCheck(
    val verdict: WidgetPriceVerdict,
    val advertised: StoredAmount,
    val invoiced: StoredAmount?
) {
    val payable: Boolean
        get() = verdict != WidgetPriceVerdict.Mismatch && verdict != WidgetPriceVerdict.Unverifiable
}

/** Tolerances from the pay draft. Providers are asked to stay within [MATCH_BASIS_POINTS]. */
object WidgetPriceTolerance {
    const val MATCH_BASIS_POINTS = 100L
    const val LIMIT_BASIS_POINTS = 1_000L
}

/**
 * Checks an invoice for [invoiceMsats] against [advertised]. [fiatPricePerBitcoin] is Rayl's
 * current rate in the advertised currency; it is not needed for `SAT` prices, which must match
 * exactly.
 */
fun checkWidgetInvoice(
    advertised: StoredAmount,
    invoiceMsats: Long,
    fiatPricePerBitcoin: Double?
): WidgetPriceCheck {
    val info = CurrencyCatalog.infoFor(advertised.normalizedCurrencyCode)
    if (info.currency == DisplayCurrency.Satoshi) {
        val exact = invoiceMsats == advertised.minor * MSATS_PER_SAT
        return WidgetPriceCheck(
            verdict = if (exact) WidgetPriceVerdict.Matches else WidgetPriceVerdict.Mismatch,
            advertised = advertised,
            invoiced = StoredAmount(invoiceMsats / MSATS_PER_SAT, info.code)
        )
    }
    val rate = fiatPricePerBitcoin?.takeIf { it.isFinite() && it > 0.0 }
        ?: return WidgetPriceCheck(WidgetPriceVerdict.Unverifiable, advertised, null)
    val converted = convertMsatsToDisplayAmount(invoiceMsats, info, rate)
        ?: return WidgetPriceCheck(WidgetPriceVerdict.Unverifiable, advertised, null)
    val invoiced = StoredAmount(converted.minor, info.code)
    val deviation = (invoiced.minor - advertised.minor) * BASIS_POINTS / advertised.minor
    val verdict = when {
        abs(deviation) <= WidgetPriceTolerance.MATCH_BASIS_POINTS -> WidgetPriceVerdict.Matches
        abs(deviation) > WidgetPriceTolerance.LIMIT_BASIS_POINTS -> WidgetPriceVerdict.Mismatch
        deviation > 0 -> WidgetPriceVerdict.Higher
        else -> WidgetPriceVerdict.Lower
    }
    return WidgetPriceCheck(verdict, advertised, invoiced)
}

private const val MSATS_PER_SAT = 1_000L
private const val BASIS_POINTS = 10_000L
