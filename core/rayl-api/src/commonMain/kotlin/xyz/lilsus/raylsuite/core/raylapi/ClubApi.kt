package xyz.lilsus.raylsuite.core.raylapi

import kotlinx.serialization.Serializable

/**
 * Rayl Club endpoints on the Rayl backend. Every request carries the member's Supabase access
 * token as `Authorization: Bearer <token>`; failures answer with a [RaylApiError] whose `error`
 * is one of the [ClubApiError] codes.
 */
object ClubApi {
    /** `GET`: the signed-in member's account, created on first use. */
    const val ME_PATH = "/v1/club/me"

    /** `POST` a [RedeemInviteRequest]: binds an invite to the signed-in account. */
    const val REDEEM_INVITE_PATH = "/v1/club/invites/redeem"
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
}

/** A Club account. [invite] is null until the member redeems an invite. */
@Serializable
data class ClubAccount(val id: String, val invite: ClubInvite? = null)

/**
 * The invite an account redeemed: the tiers it may join and whether the annual fee is waived
 * (comped testers and App Review).
 */
@Serializable
data class ClubInvite(val tierCodes: List<String>, val feeWaived: Boolean)

/** [code] as the member typed it; the backend ignores case, spaces, and dashes. */
@Serializable
data class RedeemInviteRequest(val code: String)
