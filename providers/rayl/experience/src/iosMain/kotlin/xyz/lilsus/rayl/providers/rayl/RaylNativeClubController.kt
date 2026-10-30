package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.dateWithTimeIntervalSince1970
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubError
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubIdToken
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubNonce
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubSignInProvider
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubState
import xyz.lilsus.rayl.providers.rayl.integration.club.RaylClub
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

/** A tier the member can join, with its button title. */
data class RaylNativeClubOffer(
    val tierCode: String,
    val name: String,
    val terms: String,
    val joinTitle: String
)

data class RaylNativeClubSnapshot(
    val title: String,
    /** The settings row's subtitle. */
    val status: String,
    /**
     * `loading`, `signedOut`, `accountFailed`, `invite`, `choose`, `pay`, `member`, or `none`
     * (joining isn't offered in this app: status only).
     */
    val stage: String,
    val busy: Boolean,
    val intro: String,
    val error: String?,
    val retryTitle: String,
    val inviteBody: String,
    val inviteCodeLabel: String,
    val redeemTitle: String,
    val chooseTitle: String,
    val offers: List<RaylNativeClubOffer>,
    val payTitle: String,
    val payAmount: String?,
    val payWithRaylTitle: String,
    val copyInvoiceTitle: String,
    val waitingTitle: String,
    val invoice: String?,
    val memberTitle: String,
    val tierLine: String?,
    val creditLine: String?,
    val creditAvailable: String?,
    val validUntil: String?,
    val memberIdLine: String?,
    val noMembership: String,
    val signOutTitle: String
)

/** Rayl Club on iOS. Swift runs Sign in with Apple; this exchanges its ID token for a session. */
class RaylNativeClubController internal constructor(
    private val club: RaylClub,
    private val languageChanges: Flow<*>,
    private val onPayFee: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appleNonce: ClubNonce? = null

    fun observe(onChange: (RaylNativeClubSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(club.state, languageChanges) { state, _ -> snapshot(state) }.collect(onChange)
        }
        return { job.cancel() }
    }

    /** The hashed nonce for an Apple sign-in request; the raw nonce is kept for its completion. */
    fun appleRequestNonce(): String = ClubNonce.generate().also { appleNonce = it }.hashed

    fun completeAppleSignIn(idToken: String) {
        val nonce = appleNonce ?: return club.signInFailed(cancelled = false)
        appleNonce = null
        club.signIn(ClubIdToken(ClubSignInProvider.Apple, idToken, nonce))
    }

    fun failAppleSignIn(cancelled: Boolean) {
        appleNonce = null
        club.signInFailed(cancelled)
    }

    fun redeem(code: String) = club.redeem(code)

    fun join(tierCode: String) = club.join(tierCode)

    /** Opens the usual payment confirmation for the pending fee invoice. */
    fun payFee() {
        val account = (club.state.value as? ClubState.SignedIn)?.account
        account?.membership?.payment?.invoice?.let(onPayFee)
    }

    fun reload() = club.reload()

    fun dismissError() = club.dismissError()

    fun signOut() = club.signOut()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(state: ClubState): RaylNativeClubSnapshot {
        val signedIn = state as? ClubState.SignedIn
        val account = signedIn?.account
        val membership = account?.membership
        val payment = membership?.payment
        val active = membership?.status == ClubMembershipStatus.ACTIVE
        val stage = when {
            state == ClubState.Restoring -> "loading"
            state is ClubState.SignedOut -> "signedOut"
            account == null -> if (signedIn?.working == true) "loading" else "accountFailed"
            account.invite == null -> "invite"
            active -> "member"
            payment != null -> "pay"
            account.offers.isNotEmpty() -> "choose"
            else -> "none"
        }
        val error = when (state) {
            is ClubState.SignedOut -> state.error
            is ClubState.SignedIn -> state.error
            ClubState.Restoring -> null
        }
        val formatter = currentAmountFormatter()
        return RaylNativeClubSnapshot(
            title = text("rayl_club"),
            status = status(stage, account),
            stage = stage,
            busy = (state as? ClubState.SignedOut)?.signingIn == true || signedIn?.working == true,
            intro = text("rayl_club_intro"),
            error = error?.let(::errorText),
            retryTitle = text("rayl_retry"),
            inviteBody = text("rayl_club_invite_body"),
            inviteCodeLabel = text("rayl_club_invite_code"),
            redeemTitle = text("rayl_club_redeem"),
            chooseTitle = text("rayl_club_choose_title"),
            offers = account?.offers.orEmpty().map { offer ->
                RaylNativeClubOffer(
                    tierCode = offer.tierCode,
                    name = tierName(offer.tierCode),
                    terms = format("rayl_club_offer", offer.feeUsd, offer.creditLineUsd),
                    joinTitle = text(
                        if (offer.feeWaived) "rayl_club_join_free" else "rayl_club_join"
                    )
                )
            },
            payTitle = text("rayl_club_pay_title"),
            payAmount = payment?.let {
                format(
                    "rayl_club_pay_amount",
                    formatter.format(DisplayAmount(it.amountSat, DisplayCurrency.Satoshi)),
                    it.feeUsd
                )
            },
            payWithRaylTitle = text("rayl_club_pay_with_rayl"),
            copyInvoiceTitle = text("rayl_copy_invoice"),
            waitingTitle = text("rayl_waiting_for_payment"),
            invoice = payment?.invoice,
            memberTitle = text("rayl_club_member_title"),
            tierLine = membership?.takeIf {
                active
            }?.let { format("rayl_club_tier", tierName(it.tierCode)) },
            creditLine = membership?.creditLimitSat?.takeIf { active }?.let {
                format(
                    "rayl_club_credit_line",
                    formatter.format(DisplayAmount(it, DisplayCurrency.Satoshi))
                )
            },
            creditAvailable = membership?.creditAvailableSat?.takeIf { active }?.let {
                available(formatter.format(DisplayAmount(it, DisplayCurrency.Satoshi)))
            },
            validUntil = clubEpochSeconds(membership?.termEnd)?.takeIf { active }?.let {
                format("rayl_club_valid_until", mediumDate(it))
            },
            memberIdLine = account?.memberId?.takeIf {
                it.isNotEmpty()
            }?.let { format("rayl_club_member_id", it) },
            noMembership = text("rayl_club_no_membership"),
            signOutTitle = text("rayl_club_sign_out")
        )
    }

    private fun status(stage: String, account: ClubAccount?): String = when (stage) {
        "signedOut" -> text("rayl_club_status_signed_out")

        "invite" -> text("rayl_club_status_invite_needed")

        // Next to the pocket's spending power (US-11).
        "member" -> account?.membership?.creditAvailableSat?.let {
            available(currentAmountFormatter().format(DisplayAmount(it, DisplayCurrency.Satoshi)))
        } ?: format("rayl_club_status_member", tierName(account?.membership?.tierCode.orEmpty()))

        "pay" -> text("rayl_club_status_pending")

        "choose" -> text("rayl_club_status_choose")

        "none" -> text("rayl_club_no_membership")

        else -> text("rayl_balance_updating")
    }

    private fun available(amount: String): String = format("rayl_club_credit_available", amount)

    private fun tierName(code: String): String = text(
        when (code) {
            "plus" -> "rayl_club_tier_plus"
            "test" -> "rayl_club_tier_test"
            else -> "rayl_club_tier_member"
        }
    )

    private fun format(key: String, argument: String): String =
        nativeString(NativeStringResource("RaylExperience", key), argument)

    private fun format(key: String, first: String, second: String): String =
        nativeString(NativeStringResource("RaylExperience", key), first, second)

    private fun mediumDate(epochSeconds: Long): String = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))

    private fun errorText(error: ClubError): String = text(
        when (error) {
            ClubError.Network -> "rayl_club_error_network"
            ClubError.Unavailable -> "rayl_club_error_unavailable"
            ClubError.SignInFailed -> "rayl_club_error_sign_in"
            ClubError.SessionExpired -> "rayl_club_error_session"
            ClubError.InviteNotFound -> "rayl_club_error_invite_not_found"
            ClubError.InviteUsed -> "rayl_club_error_invite_used"
            ClubError.InviteExpired -> "rayl_club_error_invite_expired"
            ClubError.AlreadyInvited -> "rayl_club_error_already_invited"
            ClubError.NotInvited -> "rayl_club_error_not_invited"
            ClubError.TierNotOffered -> "rayl_club_error_tier_not_offered"
            ClubError.AlreadyMember -> "rayl_club_error_already_member"
        }
    )
}
