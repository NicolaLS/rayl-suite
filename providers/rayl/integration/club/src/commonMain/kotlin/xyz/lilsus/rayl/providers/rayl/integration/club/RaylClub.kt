package xyz.lilsus.rayl.providers.rayl.integration.club

import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

enum class ClubPlatform(internal val apiName: String) {
    Ios("ios"),
    Android("android")
}

sealed interface ClubState {
    /** Reading the saved session. */
    data object Restoring : ClubState

    data class SignedOut(val signingIn: Boolean = false, val error: ClubError? = null) : ClubState

    /** [account] is null until it loads; [error] explains the last failed action. */
    data class SignedIn(
        val account: ClubAccount? = null,
        val working: Boolean = false,
        val error: ClubError? = null
    ) : ClubState
}

/**
 * The member's Rayl Club session and account. The session belongs to the person rather than a
 * wallet connection: it survives leaving Rayl for the wallet chooser and ends only through
 * [signOut] (SPEC-wallet-lifecycle).
 */
class RaylClub internal constructor(
    config: ClubConfig,
    private val store: SecureStringStore,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    platform: ClubPlatform,
    private val paymentPollInterval: Duration = 3.seconds
) {
    private val auth = SupabaseAuth(http, config, now)
    private val api = ClubApiClient(http, config.backendUrl, platform.apiName)
    private var paymentPoll: Job? = null
    private val sessionLock = Mutex()
    private var session: ClubSession? = null

    private val mutableState = MutableStateFlow<ClubState>(ClubState.Restoring)
    val state: StateFlow<ClubState> = mutableState.asStateFlow()

    init {
        session = readSession()
        mutableState.value = if (session == null) ClubState.SignedOut() else ClubState.SignedIn()
        if (session != null) reload()
    }

    /** Exchanges a platform ID token for a session and loads the account. */
    fun signIn(token: ClubIdToken) {
        if ((state.value as? ClubState.SignedOut)?.signingIn == true) return
        mutableState.value = ClubState.SignedOut(signingIn = true)
        scope.launch {
            settle(
                try {
                    val signedIn = auth.signIn(token)
                    sessionLock.withLock { saveSession(signedIn) }
                    ClubState.SignedIn(account = authorized(api::me))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure(error, signingIn = true)
                }
            )
        }
    }

    /** The platform's sign-in UI failed; [cancelled] means the member closed it. */
    fun signInFailed(cancelled: Boolean) {
        mutableState.value =
            ClubState.SignedOut(error = ClubError.SignInFailed.takeUnless { cancelled })
    }

    fun reload() = signedInAction { authorized(api::me) }

    fun redeem(code: String) = signedInAction { authorized { api.redeem(it, code) } }

    /** Starts a membership in an offered tier; a pending fee is then watched until paid. */
    fun join(tierCode: String) = signedInAction { authorized { api.join(it, tierCode) } }

    fun dismissError() = mutableState.update {
        when (it) {
            is ClubState.SignedOut -> it.copy(error = null)
            is ClubState.SignedIn -> it.copy(error = null)
            ClubState.Restoring -> it
        }
    }

    fun signOut() {
        paymentPoll?.cancel()
        scope.launch {
            val ended = sessionLock.withLock {
                mutableState.value = ClubState.SignedOut()
                session.also { clearSession() }
            }
            ended?.let { auth.signOut(it) }
        }
    }

    fun close() {
        scope.cancel()
        http.close()
    }

    private fun signedInAction(action: suspend () -> ClubAccount) {
        val current = state.value as? ClubState.SignedIn ?: return
        if (current.working) return
        mutableState.value = current.copy(working = true, error = null)
        scope.launch {
            settle(
                try {
                    ClubState.SignedIn(account = action())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure(error, signingIn = false)
                }
            )
        }
    }

    /** Publishes [result] unless the member signed out while it was in flight. */
    private suspend fun settle(result: ClubState) {
        sessionLock.withLock {
            if (result is ClubState.SignedIn && session == null) return
            mutableState.value = result
        }
        watchPendingPayment()
    }

    /**
     * While a fee invoice is open, re-reads the account until the backend reports the membership
     * active or the invoice expires, so paying from the pocket (or any wallet) shows up by itself.
     */
    private fun watchPendingPayment() {
        val pending = (state.value as? ClubState.SignedIn)?.account?.membership
            ?.takeIf { it.status == ClubMembershipStatus.PENDING_PAYMENT && it.payment != null }
        if (pending == null || paymentPoll?.isActive == true) return
        paymentPoll = scope.launch {
            while (true) {
                delay(paymentPollInterval)
                val current = state.value as? ClubState.SignedIn ?: return@launch
                if (current.working) continue
                val account = try {
                    authorized(api::me)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
                sessionLock.withLock {
                    if (session != null && state.value is ClubState.SignedIn) {
                        mutableState.value = ClubState.SignedIn(account = account)
                    }
                }
                val membership = account.membership
                if (membership?.status != ClubMembershipStatus.PENDING_PAYMENT ||
                    membership.payment == null
                ) {
                    return@launch
                }
            }
        }
    }

    private suspend fun failure(error: Exception, signingIn: Boolean): ClubState {
        val reason = (error as? ClubException)?.error ?: ClubError.Unavailable
        if (reason == ClubError.SessionExpired) {
            sessionLock.withLock { clearSession() }
            return ClubState.SignedOut(error = reason)
        }
        if (signingIn && session == null) return ClubState.SignedOut(error = reason)
        val account = (state.value as? ClubState.SignedIn)?.account
        return ClubState.SignedIn(account = account, error = reason)
    }

    /** Calls the backend with a fresh access token, refreshing once if the backend rejects it. */
    private suspend fun <T> authorized(call: suspend (String) -> T): T {
        try {
            return call(accessToken(forceRefresh = false))
        } catch (_: BackendUnauthorized) {
        }
        return try {
            call(accessToken(forceRefresh = true))
        } catch (_: BackendUnauthorized) {
            throw ClubException(ClubError.SessionExpired)
        }
    }

    private suspend fun accessToken(forceRefresh: Boolean): String = sessionLock.withLock {
        val current = session ?: throw ClubException(ClubError.SessionExpired)
        if (!forceRefresh && current.expiresAt - now() > REFRESH_MARGIN_SECONDS) {
            return@withLock current.accessToken
        }
        auth.refresh(current).also(::saveSession).accessToken
    }

    private fun readSession(): ClubSession? = store.getStringOrNull(SESSION_KEY)?.let {
        runCatching { json.decodeFromString<ClubSession>(it) }.getOrNull()
    }

    private fun saveSession(value: ClubSession) {
        session = value
        store.putString(SESSION_KEY, json.encodeToString(value))
    }

    private fun clearSession() {
        session = null
        store.remove(SESSION_KEY)
    }

    companion object {
        private const val SESSION_KEY = "session"
        private const val REFRESH_MARGIN_SECONDS = 60

        /** [store] must be dedicated to the Club and named [ClubConfig.storageName]. */
        fun create(config: ClubConfig, store: SecureStringStore, platform: ClubPlatform): RaylClub =
            RaylClub(
                config = config,
                store = store,
                http = createHttpClient(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                now = { Clock.System.now().epochSeconds },
                platform = platform
            )
    }
}
