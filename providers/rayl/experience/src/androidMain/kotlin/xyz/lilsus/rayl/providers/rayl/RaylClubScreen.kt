package xyz.lilsus.rayl.providers.rayl

import android.content.Context
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
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubError
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubIdToken
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubNonce
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubSignInProvider
import xyz.lilsus.rayl.providers.rayl.integration.club.ClubState
import xyz.lilsus.rayl.providers.rayl.integration.club.RaylClub
import xyz.lilsus.raylsuite.core.raylapi.ClubInvite
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton

/** The Rayl Club row in Rayl's settings, with the member's status. */
@Composable
internal fun RaylClubRow(club: RaylClub, onOpen: () -> Unit) {
    val state by club.state.collectAsStateWithLifecycle()
    AppSettingsRow(
        title = stringResource(R.string.rayl_club),
        subtitle = stringResource(clubStatus(state)),
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

@StringRes
private fun clubStatus(state: ClubState): Int = when (state) {
    ClubState.Restoring -> R.string.rayl_balance_updating

    is ClubState.SignedOut -> R.string.rayl_club_status_signed_out

    is ClubState.SignedIn -> when {
        state.account == null -> R.string.rayl_balance_updating
        state.account?.invite == null -> R.string.rayl_club_status_invite_needed
        else -> R.string.rayl_club_status_invited
    }
}

/** Sign in, redeem an invite, and see the invite's tiers. Plain S0 screens until designs exist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RaylClubScreen(club: RaylClub, googleWebClientId: String?, onBack: () -> Unit) {
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
                is ClubState.SignedIn -> SignedIn(club, current)
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
private fun SignedIn(club: RaylClub, state: ClubState.SignedIn) {
    val account = state.account
    when {
        account == null && state.working -> CircularProgressIndicator()

        account == null -> {
            state.error?.let { ClubErrorText(it) }
            Button(onClick = club::reload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rayl_retry))
            }
        }

        account.invite == null -> RedeemInvite(club, state)

        else -> Invited(checkNotNull(account.invite))
    }
    TextButton(onClick = club::signOut, enabled = !state.working) {
        Text(stringResource(R.string.rayl_club_sign_out))
    }
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

@Composable
private fun Invited(invite: ClubInvite) {
    Text(
        text = stringResource(R.string.rayl_club_invited_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Text(text = stringResource(R.string.rayl_club_invited_body), textAlign = TextAlign.Center)
    val tiers = invite.tierCodes.map { stringResource(tierName(it)) }.joinToString()
    Text(text = stringResource(R.string.rayl_club_tiers, tiers))
    if (invite.feeWaived) Text(text = stringResource(R.string.rayl_club_fee_waived))
}

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
            }
        ),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
}

/** Room for a 12-character code with dashes and stray spaces. */
private const val INVITE_INPUT_LIMIT = 24
