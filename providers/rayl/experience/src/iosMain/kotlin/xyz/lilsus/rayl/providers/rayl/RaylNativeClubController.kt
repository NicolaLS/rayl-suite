package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubError
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubIdToken
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubNonce
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubSignInProvider
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubState
import xyz.lilsus.rayl.providers.rayl.integration.club.RaylClub
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class RaylNativeClubSnapshot(
    val title: String,
    /** The settings row's subtitle. */
    val status: String,
    /** `loading`, `signedOut`, `accountFailed`, `invite`, or `invited`. */
    val stage: String,
    val busy: Boolean,
    val intro: String,
    val error: String?,
    val retryTitle: String,
    val inviteBody: String,
    val inviteCodeLabel: String,
    val redeemTitle: String,
    val invitedTitle: String,
    val invitedBody: String,
    val tiers: String?,
    val feeWaived: String?,
    val signOutTitle: String
)

/** Rayl Club on iOS. Swift runs Sign in with Apple; this exchanges its ID token for a session. */
class RaylNativeClubController internal constructor(
    private val club: RaylClub,
    private val languageChanges: Flow<*>
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

    fun reload() = club.reload()

    fun dismissError() = club.dismissError()

    fun signOut() = club.signOut()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(state: ClubState): RaylNativeClubSnapshot {
        val signedIn = state as? ClubState.SignedIn
        val invite = signedIn?.account?.invite
        val stage = when {
            state == ClubState.Restoring -> "loading"

            state is ClubState.SignedOut -> "signedOut"

            signedIn?.account == null -> if (signedIn?.working ==
                true
            ) {
                "loading"
            } else {
                "accountFailed"
            }

            invite == null -> "invite"

            else -> "invited"
        }
        val error = when (state) {
            is ClubState.SignedOut -> state.error
            is ClubState.SignedIn -> state.error
            ClubState.Restoring -> null
        }
        return RaylNativeClubSnapshot(
            title = text("rayl_club"),
            status = text(
                when (stage) {
                    "signedOut" -> "rayl_club_status_signed_out"
                    "invite" -> "rayl_club_status_invite_needed"
                    "invited" -> "rayl_club_status_invited"
                    else -> "rayl_balance_updating"
                }
            ),
            stage = stage,
            busy = (state as? ClubState.SignedOut)?.signingIn == true || signedIn?.working == true,
            intro = text("rayl_club_intro"),
            error = error?.let(::errorText),
            retryTitle = text("rayl_retry"),
            inviteBody = text("rayl_club_invite_body"),
            inviteCodeLabel = text("rayl_club_invite_code"),
            redeemTitle = text("rayl_club_redeem"),
            invitedTitle = text("rayl_club_invited_title"),
            invitedBody = text("rayl_club_invited_body"),
            tiers = invite?.tierCodes?.joinToString { tierName(it) }?.let {
                nativeString(NativeStringResource("RaylExperience", "rayl_club_tiers"), it)
            },
            feeWaived = text("rayl_club_fee_waived").takeIf { invite?.feeWaived == true },
            signOutTitle = text("rayl_club_sign_out")
        )
    }

    private fun tierName(code: String): String =
        text(if (code == "plus") "rayl_club_tier_plus" else "rayl_club_tier_member")

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
        }
    )
}
