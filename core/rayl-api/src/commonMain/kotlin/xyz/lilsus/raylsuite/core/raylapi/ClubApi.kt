package xyz.lilsus.raylsuite.core.raylapi

import kotlinx.serialization.Serializable

/**
 * Rayl Club endpoints on the Rayl backend. Every request carries the member's Supabase access
 * token as `Authorization: Bearer <token>`; failures answer with a [RaylApiError] whose `error`
 * is one of the [ClubApiError] codes.
 */
object ClubApi {
    /**
     * `GET`: the signed-in member's account, created on first use. Pass [PLATFORM_PARAMETER] so
     * the backend can decide [ClubAccount.joinInApp].
     */
    const val ME_PATH = "/v1/club/me"

    /** `ios` or `android`. */
    const val PLATFORM_PARAMETER = "platform"

    /** `POST` a [RedeemInviteRequest]: binds an invite to the signed-in account. */
    const val REDEEM_INVITE_PATH = "/v1/club/invites/redeem"

    /**
     * `POST` a [JoinRequest] (with [PLATFORM_PARAMETER]): starts a membership in an offered tier
     * and answers the updated [ClubAccount]. A fee-waived invite activates it at once; otherwise
     * the membership waits for its [ClubFeePayment].
     */
    const val MEMBERSHIPS_PATH = "/v1/club/memberships"

    /**
     * `POST` a [CreditQuoteRequest]: whether Club credit can pay an invoice, and its routing fee.
     * Reserves nothing. A decline answers one of the credit [ClubApiError] codes.
     */
    const val CREDIT_QUOTES_PATH = "/v1/club/credit/quotes"

    /**
     * `POST` a [CreditSpendRequest]: the treasury pays the invoice and charges the member's line.
     * Repeating a request with the same [CreditSpendRequest.idempotencyKey] answers the original
     * [CreditSpend] and never pays twice.
     */
    const val CREDIT_SPENDS_PATH = "/v1/club/credit/spends"
}

object ClubMembershipStatus {
    const val PENDING_PAYMENT = "pending_payment"
    const val ACTIVE = "active"
}

object ClubApiError {
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_REQUEST = "invalid_request"

    /** The Club is temporarily unreachable (HTTP 503); retrying later may succeed. */
    const val UNAVAILABLE = "club_unavailable"
    const val INVITE_NOT_FOUND = "invite_not_found"
    const val INVITE_USED = "invite_used"
    const val INVITE_EXPIRED = "invite_expired"

    /** The account already redeemed an invite; one account holds at most one. */
    const val ALREADY_INVITED = "already_invited"
    const val NOT_INVITED = "not_invited"
    const val TIER_NOT_OFFERED = "tier_not_offered"
    const val ALREADY_MEMBER = "already_member"

    /** Credit declines. Only [INVOICE_INVALID] and [INVOICE_EXPIRED] concern the invoice itself. */
    const val NOT_A_MEMBER = "not_a_member"
    const val OVER_LIMIT = "over_limit"
    const val INVOICE_INVALID = "invoice_invalid"
    const val INVOICE_EXPIRED = "invoice_expired"

    /** A Rayl Club invoice, such as the annual fee, cannot be paid with Club credit. */
    const val OWN_INVOICE = "own_invoice"

    /** The routing fee rose above [CreditSpendRequest.maxFeeMsat]; nothing was paid. */
    const val FEE_CHANGED = "fee_changed"
}

object ClubCreditSpendStatus {
    const val SETTLED = "settled"
    const val FAILED = "failed"

    /** Sent, outcome not known yet; the amount stays reserved until it is. */
    const val PENDING = "pending"
}

/**
 * A Club account. [invite] is null until the member redeems an invite. [memberId] identifies the
 * account to the member, e.g. for joining on the web.
 */
@Serializable
data class ClubAccount(
    val id: String,
    val memberId: String = "",
    val invite: ClubInvite? = null,
    /** The active membership, or else the latest one awaiting its fee. */
    val membership: ClubMembership? = null,
    /** Whether this app may offer joining; when false it shows status only. */
    val joinInApp: Boolean = false,
    /** Tiers the member can join now; empty unless [joinInApp] and not yet a member. */
    val offers: List<ClubTierOffer> = emptyList()
)

/** Amounts in whole US dollars as decimal strings, e.g. "60". */
@Serializable
data class ClubTierOffer(
    val tierCode: String,
    val feeUsd: String,
    val creditLineUsd: String,
    val feeWaived: Boolean
)

/**
 * [status] is a [ClubMembershipStatus]. An active membership has [creditLimitSat], fixed for the
 * term ending at [termEnd] (ISO-8601); a pending one has [payment].
 */
@Serializable
data class ClubMembership(
    val tierCode: String,
    val status: String,
    val creditLimitSat: Long? = null,
    /** The limit less what is owed and what is reserved for payments in flight. */
    val creditAvailableSat: Long? = null,
    val termEnd: String? = null,
    val payment: ClubFeePayment? = null
)

/** The annual fee as a Lightning invoice, valid until [expiresAt] (ISO-8601). */
@Serializable
data class ClubFeePayment(
    val invoice: String,
    val amountSat: Long,
    val feeUsd: String,
    val expiresAt: String
)

@Serializable
data class JoinRequest(val tierCode: String)

/**
 * The invite an account redeemed: the tiers it may join and whether the annual fee is waived
 * (comped testers and App Review).
 */
@Serializable
data class ClubInvite(val tierCodes: List<String>, val feeWaived: Boolean)

/** [code] as the member typed it; the backend ignores case, spaces, and dashes. */
@Serializable
data class RedeemInviteRequest(val code: String)

/** [amountMsat] is required for an invoice without an amount and must match one that has it. */
@Serializable
data class CreditQuoteRequest(val invoice: String, val amountMsat: Long? = null)

/** What paying with credit would charge the line: [amountMsat] plus [feeMsat]. */
@Serializable
data class CreditQuote(val amountMsat: Long, val feeMsat: Long)

/**
 * [idempotencyKey] is chosen by the app, one per confirmed payment. [maxFeeMsat] is the fee the
 * member saw; a higher fee declines with [ClubApiError.FEE_CHANGED].
 */
@Serializable
data class CreditSpendRequest(
    val idempotencyKey: String,
    val invoice: String,
    val amountMsat: Long? = null,
    val maxFeeMsat: Long
)

/** [status] is a [ClubCreditSpendStatus]; [feeMsat] and [preimage] are known once settled. */
@Serializable
data class CreditSpend(
    val id: String,
    val status: String,
    val amountMsat: Long,
    val feeMsat: Long? = null,
    val preimage: String? = null
)
