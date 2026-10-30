package xyz.lilsus.raylsuite.backend.club

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A tier's current terms; memberships snapshot them when they start. A [testOnly] tier can only
 * be put on invites where test tiers are allowed (Regtest), and joining needs such an invite.
 */
data class ClubTier(
    val code: String,
    val feeUsdCents: Long,
    val lineUsdCents: Long,
    val testOnly: Boolean = false
)

/** Beta tiers (P-07): the line equals the fee, so the fee covers the credit risk. */
val CLUB_TIERS: List<ClubTier> = listOf(
    ClubTier(code = "member", feeUsdCents = 60_00, lineUsdCents = 60_00),
    ClubTier(code = "plus", feeUsdCents = 150_00, lineUsdCents = 150_00),
    // Cheap end-to-end testing on Regtest.
    ClubTier(code = "test", feeUsdCents = 1_00, lineUsdCents = 1_00, testOnly = true)
)

/** The tier codes an operator may put on invites. */
fun inviteTierCodes(allowTestTiers: Boolean): Set<String> =
    CLUB_TIERS.filter { allowTestTiers || !it.testOnly }.map(ClubTier::code).toSet()

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
