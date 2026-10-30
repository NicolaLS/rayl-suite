package xyz.lilsus.raylsuite.backend.club

import java.math.BigDecimal
import java.math.RoundingMode

/** A tier's current terms; memberships snapshot them when they start. */
data class ClubTier(val code: String, val feeUsdCents: Long, val lineUsdCents: Long)

/** Beta tiers (P-07): the line equals the fee, so the fee covers the credit risk. */
val CLUB_TIERS: List<ClubTier> = listOf(
    ClubTier(code = "member", feeUsdCents = 60_00, lineUsdCents = 60_00),
    ClubTier(code = "plus", feeUsdCents = 150_00, lineUsdCents = 150_00)
)

val CLUB_TIER_CODES: Set<String> = CLUB_TIERS.map(ClubTier::code).toSet()

fun clubTier(code: String): ClubTier? = CLUB_TIERS.firstOrNull { it.code == code }

/** USD cents to sats at [btcUsd], rounded up so the member never pays less than the price. */
fun feeSats(usdCents: Long, btcUsd: BigDecimal): Long =
    BigDecimal(usdCents).multiply(SATS_PER_BTC_PER_CENT).divide(btcUsd, 0, RoundingMode.CEILING)
        .longValueExact()

/** USD cents to msat at [btcUsd], rounded down so the line never exceeds its USD value. */
fun lineMsat(usdCents: Long, btcUsd: BigDecimal): Long =
    BigDecimal(usdCents).multiply(MSAT_PER_BTC_PER_CENT).divide(btcUsd, 0, RoundingMode.FLOOR)
        .longValueExact()

/** "60" for 6000 cents, "60.50" for 6050. */
fun usdText(cents: Long): String {
    val dollars = BigDecimal(cents).movePointLeft(2)
    return if (cents % 100 == 0L) dollars.toBigInteger().toString() else dollars.toPlainString()
}

// 1 USD = 1e8 / price sats, so 1 cent = 1e6 / price sats = 1e9 / price msat.
private val SATS_PER_BTC_PER_CENT = BigDecimal(1_000_000)
private val MSAT_PER_BTC_PER_CENT = BigDecimal(1_000_000_000)
