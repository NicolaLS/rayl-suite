package xyz.lilsus.rayl.providers.rayl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.Date
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubError
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubIdToken
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubNonce
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubSignInProvider
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubState
import xyz.lilsus.rayl.providers.rayl.integration.club.RaylClub
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.raylapi.ClubFeePayment
import xyz.lilsus.raylsuite.core.raylapi.ClubMembership
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.raylapi.ClubTierOffer
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter

/** The Rayl Club row in Rayl's settings, with the member's status. */
@Composable
internal fun RaylClubRow(club: RaylClub, onOpen: () -> Unit) {
    val state by club.state.collectAsStateWithLifecycle()
    AppSettingsRow(
        title = stringResource(R.string.rayl_club),
        subtitle = clubStatus(state),
        icon = Icons.Filled.WorkspacePremium,
        onClick = onOpen
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
        )
    }
}

@Composable
private fun clubStatus(state: ClubState): String {
    val account = (state as? ClubState.SignedIn)?.account
    val membership = account?.membership
    return when {
        state is ClubState.SignedOut -> stringResource(R.string.rayl_club_status_signed_out)

        account == null -> stringResource(R.string.rayl_balance_updating)

        account.invite == null -> stringResource(R.string.rayl_club_status_invite_needed)

        membership?.status == ClubMembershipStatus.ACTIVE ->
            stringResource(
                R.string.rayl_club_status_member,
                stringResource(tierName(membership.tierCode))
            )

        membership?.payment != null -> stringResource(R.string.rayl_club_status_pending)

        account.offers.isNotEmpty() -> stringResource(R.string.rayl_club_status_choose)

        else -> stringResource(R.string.rayl_club_no_membership)
    }
}

/** Sign in, redeem an invite, and see the invite's tiers. Plain S0 screens until designs exist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaylClubScreen(
    club: RaylClub,
    googleWebClientId: String?,
    onPayFee: (invoice: String) -> Unit,
    onBack: () -> Unit
) {
    val state by club.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rayl_club)) },
                navigationIcon = { BackIconButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when (val current = state) {
                ClubState.Restoring -> CircularProgressIndicator()
                is ClubState.SignedOut -> SignedOut(club, current, googleWebClientId)
                is ClubState.SignedIn -> SignedIn(club, current, onPayFee)
            }
        }
    }
}

@Composable
private fun SignedOut(club: RaylClub, state: ClubState.SignedOut, googleWebClientId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(text = stringResource(R.string.rayl_club_intro), textAlign = TextAlign.Center)
    state.error?.let { ClubErrorText(it) }
    when {
        state.signingIn -> CircularProgressIndicator()

        googleWebClientId == null -> Text(
            text = stringResource(R.string.rayl_club_sign_in_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        else -> Button(
            onClick = { scope.launch { signInWithGoogle(context, club, googleWebClientId) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.rayl_club_sign_in_google))
        }
    }
}

/**
 * Google's sign-in sheet returns an ID token bound to a fresh nonce; Supabase exchanges it for the
 * Club session. Needs the activity context Compose provides.
 */
private suspend fun signInWithGoogle(context: Context, club: RaylClub, webClientId: String) {
    val nonce = ClubNonce.generate()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetSignInWithGoogleOption.Builder(serverClientId = webClientId)
                .setNonce(nonce.hashed)
                .build()
        )
        .build()
    try {
        val credential = CredentialManager.create(
            context
        ).getCredential(context, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            club.signIn(ClubIdToken(ClubSignInProvider.Google, idToken, nonce))
        } else {
            club.signInFailed(cancelled = false)
        }
    } catch (_: GetCredentialCancellationException) {
        club.signInFailed(cancelled = true)
    } catch (_: GetCredentialException) {
        club.signInFailed(cancelled = false)
    }
}

@Composable
private fun SignedIn(club: RaylClub, state: ClubState.SignedIn, onPayFee: (String) -> Unit) {
    val account = state.account
    val membership = account?.membership
    val payment = membership?.payment
    when {
        account == null && state.working -> CircularProgressIndicator()

        account == null -> {
            state.error?.let { ClubErrorText(it) }
            Button(onClick = club::reload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rayl_retry))
            }
        }

        account.invite == null -> RedeemInvite(club, state)

        membership?.status == ClubMembershipStatus.ACTIVE -> Member(membership, account.memberId)

        payment != null -> PayFee(payment, onPayFee)

        account.offers.isNotEmpty() -> ChooseTier(club, state, account.offers)

        // Joining is not offered in this app (D-16): status only, no directions elsewhere.
        else -> {
            Text(stringResource(R.string.rayl_club_no_membership))
            MemberId(account.memberId)
        }
    }
    if (account != null && account.invite != null) state.error?.let { ClubErrorText(it) }
    TextButton(onClick = club::signOut, enabled = !state.working) {
        Text(stringResource(R.string.rayl_club_sign_out))
    }
}

@Composable
private fun ChooseTier(club: RaylClub, state: ClubState.SignedIn, offers: List<ClubTierOffer>) {
    Text(
        text = stringResource(R.string.rayl_club_choose_title),
        style = MaterialTheme.typography.headlineSmall
    )
    offers.forEach { offer ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(tierName(offer.tierCode)),
                style = MaterialTheme.typography.titleMedium
            )
            Text(text = stringResource(R.string.rayl_club_offer, offer.feeUsd, offer.creditLineUsd))
            Button(
                enabled = !state.working,
                onClick = { club.join(offer.tierCode) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(joinTitle(offer)))
            }
        }
    }
}

@Composable
private fun PayFee(payment: ClubFeePayment, onPayFee: (String) -> Unit) {
    val context = LocalContext.current
    val formatter = rememberAmountFormatter()
    Text(
        text = stringResource(R.string.rayl_club_pay_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        stringResource(
            R.string.rayl_club_pay_amount,
            formatter.format(DisplayAmount(payment.amountSat, DisplayCurrency.Satoshi)),
            payment.feeUsd
        )
    )
    Button(onClick = { onPayFee(payment.invoice) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rayl_club_pay_with_rayl))
    }
    OutlinedButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("Lightning invoice", payment.invoice)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.rayl_copy_invoice))
    }
    Text(
        text = stringResource(R.string.rayl_waiting_for_payment),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Member(membership: ClubMembership, memberId: String) {
    val context = LocalContext.current
    val formatter = rememberAmountFormatter()
    Text(
        text = stringResource(R.string.rayl_club_member_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(stringResource(R.string.rayl_club_tier, stringResource(tierName(membership.tierCode))))
    membership.creditLimitSat?.let { limit ->
        Text(
            stringResource(
                R.string.rayl_club_credit_line,
                formatter.format(DisplayAmount(limit, DisplayCurrency.Satoshi))
            )
        )
    }
    clubEpochSeconds(membership.termEnd)?.let { seconds ->
        val date = DateFormat.getMediumDateFormat(context).format(Date(seconds * 1_000))
        Text(stringResource(R.string.rayl_club_valid_until, date))
    }
    MemberId(memberId)
}

@Composable
private fun MemberId(memberId: String) {
    Text(
        text = stringResource(R.string.rayl_club_member_id, memberId),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun RedeemInvite(club: RaylClub, state: ClubState.SignedIn) {
    var code by rememberSaveable { mutableStateOf("") }
    Text(text = stringResource(R.string.rayl_club_invite_body), textAlign = TextAlign.Center)
    OutlinedTextField(
        value = code,
        onValueChange = { value ->
            code = value.take(INVITE_INPUT_LIMIT)
            if (state.error != null) club.dismissError()
        },
        label = { Text(stringResource(R.string.rayl_club_invite_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false
        ),
        modifier = Modifier.fillMaxWidth()
    )
    state.error?.let { ClubErrorText(it) }
    OutlinedButton(
        enabled = !state.working && code.isNotBlank(),
        onClick = { club.redeem(code) },
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.working) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Text(stringResource(R.string.rayl_club_redeem))
        }
    }
}

@StringRes
private fun joinTitle(offer: ClubTierOffer): Int =
    if (offer.feeWaived) R.string.rayl_club_join_free else R.string.rayl_club_join

@StringRes
private fun tierName(code: String): Int = when (code) {
    "plus" -> R.string.rayl_club_tier_plus
    else -> R.string.rayl_club_tier_member
}

@Composable
private fun ClubErrorText(error: ClubError) {
    Text(
        text = stringResource(
            when (error) {
                ClubError.Network -> R.string.rayl_club_error_network
                ClubError.Unavailable -> R.string.rayl_club_error_unavailable
                ClubError.SignInFailed -> R.string.rayl_club_error_sign_in
                ClubError.SessionExpired -> R.string.rayl_club_error_session
                ClubError.InviteNotFound -> R.string.rayl_club_error_invite_not_found
                ClubError.InviteUsed -> R.string.rayl_club_error_invite_used
                ClubError.InviteExpired -> R.string.rayl_club_error_invite_expired
                ClubError.AlreadyInvited -> R.string.rayl_club_error_already_invited
                ClubError.NotInvited -> R.string.rayl_club_error_not_invited
                ClubError.TierNotOffered -> R.string.rayl_club_error_tier_not_offered
                ClubError.AlreadyMember -> R.string.rayl_club_error_already_member
            }
        ),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
}

/** Room for a 12-character code with dashes and stray spaces. */
private const val INVITE_INPUT_LIMIT = 24
