package xyz.lilsus.rayl.providers.rayl.integration.club

import fr.acinq.bitcoin.Crypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDue
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDueKind
import xyz.lilsus.raylsuite.core.raylapi.ClubFeePayment
import xyz.lilsus.raylsuite.core.raylapi.ClubInvite
import xyz.lilsus.raylsuite.core.raylapi.ClubMembership
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.raylapi.ClubRepayment
import xyz.lilsus.raylsuite.core.raylapi.ClubStatement
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLine
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLineKind
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementSummary
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

private const val NOW = 1_800_000_000L
private val ACCOUNT = ClubAccount(id = "account-1")
private val INVITED = ClubAccount(id = "account-1", invite = ClubInvite(listOf("member"), false))
private val ACTIVE = ACCOUNT.copy(
    membership = ClubMembership("member", ClubMembershipStatus.ACTIVE, creditLimitSat = 60_000)
)

class RaylClubTest {
    @Test
    fun signingInStoresTheSessionAndLoadsTheAccount() = runTest {
        val backend = FakeBackend()
        val store = MemoryStore()
        val club = club(backend, store)
        assertEquals(ClubState.SignedOut(), club.state.value)

        club.signIn(ClubIdToken(ClubSignInProvider.Apple, "apple-id-token", ClubNonce("raw-nonce")))
        club.settled()

        assertEquals(ClubState.SignedIn(account = ACCOUNT), club.state.value)
        assertEquals(
            """{"provider":"apple","id_token":"apple-id-token","nonce":"raw-nonce"}""",
            backend.bodies.getValue("/auth/v1/token?grant_type=id_token")
        )
        assertEquals("Bearer access-1", backend.authorizations.last())
        assertTrue(store.values.getValue("session").contains("refresh-1"))
    }

    @Test
    fun aSavedSessionIsRestoredAtStart() = runTest {
        val backend = FakeBackend()
        val club = club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600)))
        assertEquals(ClubState.SignedIn(working = true), club.state.value)

        club.settled()
        assertEquals(ClubState.SignedIn(account = ACCOUNT), club.state.value)
        assertEquals(listOf("Bearer access-0"), backend.authorizations)
    }

    @Test
    fun anExpiringTokenIsRefreshedFirst() = runTest {
        val backend = FakeBackend()
        val store = MemoryStore(session("access-0", "refresh-0", NOW + 30))
        club(backend, store).settled()

        assertEquals("""{"refresh_token":"refresh-0"}""", backend.bodies["/auth/v1/token?grant_type=refresh_token"])
        assertEquals(listOf("Bearer access-1"), backend.authorizations)
        assertTrue(store.values.getValue("session").contains("refresh-1"))
    }

    @Test
    fun aRejectedTokenIsRefreshedOnce() = runTest {
        val backend = FakeBackend(meStatuses = mutableListOf(HttpStatusCode.Unauthorized))
        val club = club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600)))
        club.settled()

        assertEquals(ClubState.SignedIn(account = ACCOUNT), club.state.value)
        assertEquals(listOf("Bearer access-0", "Bearer access-1"), backend.authorizations)
    }

    @Test
    fun aRevokedSessionSignsOut() = runTest {
        val backend = FakeBackend(refreshStatus = HttpStatusCode.BadRequest)
        val store = MemoryStore(session("access-0", "refresh-0", NOW))
        val club = club(backend, store)
        club.settled()

        assertEquals(ClubState.SignedOut(error = ClubError.SessionExpired), club.state.value)
        assertNull(store.values["session"])
    }

    @Test
    fun aBackendThatKeepsRejectingTheTokenSignsOut() = runTest {
        val backend = FakeBackend(
            meStatuses = mutableListOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized)
        )
        val store = MemoryStore(session("access-0", "refresh-0", NOW + 3600))
        val club = club(backend, store)
        club.settled()

        assertEquals(ClubState.SignedOut(error = ClubError.SessionExpired), club.state.value)
        assertNull(store.values["session"])
    }

    @Test
    fun redeemingAnInviteUpdatesTheAccountOrExplainsTheFailure() = runTest {
        val backend = FakeBackend()
        val club = club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600)))
        club.settled()

        backend.redeemResponse = HttpStatusCode.Conflict to """{"error":"invite_used"}"""
        club.redeem("AAAA-BBBB-CCCC")
        club.settled()
        assertEquals(ClubState.SignedIn(account = ACCOUNT, error = ClubError.InviteUsed), club.state.value)

        club.dismissError()
        backend.redeemResponse = HttpStatusCode.OK to json.encodeToString(INVITED)
        club.redeem("AAAA-BBBB-CCCC")
        club.settled()
        assertEquals(ClubState.SignedIn(account = INVITED), club.state.value)
        assertEquals("""{"code":"AAAA-BBBB-CCCC"}""", backend.bodies["/v1/club/invites/redeem"])
    }

    @Test
    fun aNetworkFailureKeepsTheSession() = runTest {
        val backend = FakeBackend(offline = true)
        val store = MemoryStore(session("access-0", "refresh-0", NOW + 3600))
        val club = club(backend, store)
        club.settled()

        assertEquals(ClubState.SignedIn(error = ClubError.Network), club.state.value)
        assertTrue(store.values.containsKey("session"))
    }

    @Test
    fun aFailedSignInStaysSignedOut() = runTest {
        val backend = FakeBackend(signInStatus = HttpStatusCode.BadRequest)
        val store = MemoryStore()
        val club = club(backend, store)
        club.signIn(ClubIdToken(ClubSignInProvider.Google, "bad", ClubNonce("n")))
        club.settled()

        assertEquals(ClubState.SignedOut(error = ClubError.SignInFailed), club.state.value)
        assertTrue(store.values.isEmpty())

        club.signInFailed(cancelled = true)
        assertEquals(ClubState.SignedOut(), club.state.value)
    }

    @Test
    fun signingOutForgetsTheSessionAndRevokesIt() = runTest {
        val backend = FakeBackend()
        val store = MemoryStore(session("access-0", "refresh-0", NOW + 3600))
        val club = club(backend, store)
        club.settled()

        club.signOut()
        club.state.first { it is ClubState.SignedOut }
        backend.loggedOut.await()
        assertEquals(ClubState.SignedOut(), club.state.value)
        assertNull(store.values["session"])
        assertTrue(backend.paths.contains("/auth/v1/logout"))
    }

    @Test
    fun joiningWatchesThePendingFeeUntilTheMembershipIsActive() = runTest {
        val pending = ACCOUNT.copy(
            membership = ClubMembership(
                tierCode = "member",
                status = ClubMembershipStatus.PENDING_PAYMENT,
                payment = ClubFeePayment("lnbcrt1fee", 60_000, "60", "2026-10-30T12:10:00Z")
            )
        )
        val active = ACCOUNT.copy(
            membership = ClubMembership("member", ClubMembershipStatus.ACTIVE, creditLimitSat = 60_000)
        )
        val backend = FakeBackend()
        val club = club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600)))
        club.settled()

        backend.redeemResponse = HttpStatusCode.OK to json.encodeToString(pending)
        backend.meAccounts += listOf(pending, active)
        club.join("member")
        club.state.first { (it as? ClubState.SignedIn)?.account == pending }
        assertEquals("""{"tierCode":"member"}""", backend.bodies["/v1/club/memberships"])

        // Polling picks up the payment without any action from the member.
        club.state.first { (it as? ClubState.SignedIn)?.account == active }
    }

    @Test
    fun creditIsNotTriedWithoutAnActiveMembership() = runTest {
        val backend = FakeBackend()
        val club = club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600)))
        club.settled()

        assertEquals(ClubCreditQuote.NotUsable, club.quote("lnbcrt1x", null))
        assertTrue(backend.paths.none { it.startsWith("/v1/club/credit") })
    }

    @Test
    fun creditQuotesFallBackToThePocketUnlessTheInvoiceIsAtFault() = runTest {
        val club = activeMember()
        suspend fun quote(status: HttpStatusCode, body: String): ClubCreditQuote {
            backend.redeemResponse = status to body
            return club.quote("lnbcrt1x", 21_000)
        }

        assertEquals(
            ClubCreditQuote.Quoted(amountMsat = 21_000, feeMsat = 3_000),
            quote(HttpStatusCode.OK, """{"amountMsat":21000,"feeMsat":3000}""")
        )
        assertEquals(
            """{"invoice":"lnbcrt1x","amountMsat":21000}""",
            backend.bodies["/v1/club/credit/quotes"]
        )
        assertEquals(
            ClubCreditQuote.NotUsable,
            quote(HttpStatusCode.Conflict, """{"error":"over_limit"}""")
        )
        assertEquals(
            ClubCreditQuote.NotUsable,
            quote(HttpStatusCode.ServiceUnavailable, """{"error":"club_unavailable"}""")
        )
        assertEquals(
            ClubCreditQuote.InvoiceRejected(expired = false),
            quote(HttpStatusCode.UnprocessableEntity, """{"error":"invoice_invalid"}""")
        )
        assertEquals(
            ClubCreditQuote.InvoiceRejected(expired = true),
            quote(HttpStatusCode.Gone, """{"error":"invoice_expired"}""")
        )
    }

    @Test
    fun aSpendIsOnlyFailedWhenTheBackendDeclinedItBeforeSending() = runTest {
        val club = activeMember()
        suspend fun spend(status: HttpStatusCode, body: String): ClubCreditOutcome {
            backend.redeemResponse = status to body
            return club.spend("lnbcrt1x", null, maxFeeMsat = 3_000, idempotencyKey = "key-1")
        }

        assertEquals(
            ClubCreditOutcome.Paid(feeMsat = 2_000, preimage = "aa"),
            spend(
                HttpStatusCode.OK,
                """{"id":"s","status":"settled","amountMsat":1000,"feeMsat":2000,"preimage":"aa"}"""
            )
        )
        assertEquals(
            """{"idempotencyKey":"key-1","invoice":"lnbcrt1x","maxFeeMsat":3000}""",
            backend.bodies["/v1/club/credit/spends"]
        )
        assertEquals(
            ClubCreditOutcome.Unknown,
            spend(HttpStatusCode.OK, """{"id":"s","status":"pending","amountMsat":1000}""")
        )
        assertEquals(
            ClubCreditOutcome.Failed,
            spend(HttpStatusCode.OK, """{"id":"s","status":"failed","amountMsat":1000}""")
        )
        assertEquals(
            ClubCreditOutcome.Failed,
            spend(HttpStatusCode.Conflict, """{"error":"fee_changed"}""")
        )
        assertEquals(
            ClubCreditOutcome.Unknown,
            spend(HttpStatusCode.ServiceUnavailable, """{"error":"club_unavailable"}""")
        )
    }

    @Test
    fun repayingOpensTheInvoiceAndWatchesItUntilPaid() = runTest {
        val club = activeMember()
        val due = ClubAmountDue(503, ClubAmountDueKind.STATEMENT, "2026-11-14T00:00:00Z")
        val open = ACTIVE.copy(
            membership = ACTIVE.membership?.copy(
                balanceSat = 503,
                amountDue = due,
                repayment = ClubRepayment("lnbcrt1repay", 503, "2026-11-01T13:00:00Z")
            )
        )
        val paid = ACTIVE.copy(membership = ACTIVE.membership?.copy(balanceSat = 0))
        backend.redeemResponse = HttpStatusCode.OK to json.encodeToString(open)
        backend.meAccounts += listOf(open, paid)

        val confirmations = mutableListOf<String>()
        club.repay(onInvoice = confirmations::add)
        club.state.first { (it as? ClubState.SignedIn)?.account == open }
        assertEquals("{}", backend.bodies["/v1/club/repayments"])
        // One tap reaches the payment confirmation.
        assertEquals(listOf("lnbcrt1repay"), confirmations)

        // Polling picks up the repayment without any action from the member.
        club.state.first { (it as? ClubState.SignedIn)?.account == paid }
    }

    @Test
    fun repayingWhenNothingIsDueReloadsTheAccount() = runTest {
        val club = activeMember()
        val paid = ACTIVE.copy(membership = ACTIVE.membership?.copy(balanceSat = 0))
        backend.redeemResponse = HttpStatusCode.Conflict to """{"error":"nothing_due"}"""
        backend.meAccounts += paid

        val confirmations = mutableListOf<String>()
        club.repay(onInvoice = confirmations::add)
        assertEquals(ClubState.SignedIn(account = paid), club.settled())
        assertEquals(emptyList(), confirmations)
    }

    @Test
    fun theStatementLoadsOnDemand() = runTest {
        val club = activeMember()
        val statement = ClubStatement(
            statement = ClubStatementSummary(
                periodStart = "2026-10-01T00:00:00Z",
                periodEnd = "2026-11-01T00:00:00Z",
                closingSat = 503,
                remainingSat = 503,
                dueAt = "2026-11-15T00:00:00Z",
                paid = false
            ),
            lines = listOf(ClubStatementLine(ClubStatementLineKind.SPEND, "2026-10-30T17:00:00Z", 503)),
            currentPeriodStart = "2026-11-01T00:00:00Z"
        )
        backend.redeemResponse = HttpStatusCode.OK to json.encodeToString(statement)
        club.loadStatement()
        assertEquals(
            ClubStatementState.Loaded(statement),
            club.statement.first { it != ClubStatementState.Loading }
        )

        backend.redeemResponse = HttpStatusCode.ServiceUnavailable to """{"error":"club_unavailable"}"""
        club.loadStatement()
        assertEquals(
            ClubStatementState.Failed(ClubError.Unavailable),
            club.statement.first { it != ClubStatementState.Loading }
        )
    }

    private val backend = FakeBackend()

    private suspend fun TestScope.activeMember(): RaylClub {
        backend.meAccounts += ACTIVE
        return club(backend, MemoryStore(session("access-0", "refresh-0", NOW + 3600))).also {
            it.settled()
        }
    }

    @Test
    fun theNonceHashIsTheHexSha256OfTheRawNonce() {
        val nonce = ClubNonce.generate()
        assertEquals(64, nonce.raw.length)
        assertEquals(Crypto.sha256(nonce.raw.encodeToByteArray()).toHexString(), nonce.hashed)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ClubNonce("hello").hashed
        )
    }

    private fun TestScope.club(backend: FakeBackend, store: MemoryStore) = RaylClub(
        config = ClubConfig.Regtest.copy(backendUrl = "https://backend", supabaseUrl = "https://supabase"),
        store = store,
        http = HttpClient(MockEngine { backend.handle(this, it) }),
        scope = backgroundScope,
        now = { NOW },
        platform = ClubPlatform.Ios
    )

    /** Waits until no sign-in or account request is in flight. */
    private suspend fun RaylClub.settled(): ClubState = state.first {
        when (it) {
            ClubState.Restoring -> false
            is ClubState.SignedOut -> !it.signingIn
            is ClubState.SignedIn -> !it.working
        }
    }

    private fun session(access: String, refresh: String, expiresAt: Long) =
        mapOf("session" to json.encodeToString(ClubSession(access, refresh, expiresAt)))
}

private class FakeBackend(
    val signInStatus: HttpStatusCode = HttpStatusCode.OK,
    val refreshStatus: HttpStatusCode = HttpStatusCode.OK,
    val meStatuses: MutableList<HttpStatusCode> = mutableListOf(),
    val offline: Boolean = false
) {
    var redeemResponse: Pair<HttpStatusCode, String> = HttpStatusCode.OK to json.encodeToString(INVITED)
    val bodies = mutableMapOf<String, String>()
    val paths = mutableListOf<String>()
    val authorizations = mutableListOf<String>()
    val loggedOut = CompletableDeferred<Unit>()

    /** Answers for successive `me` calls; [ACCOUNT] once empty. */
    val meAccounts = mutableListOf<ClubAccount>()

    suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
        // Club paths carry ?platform=…; Supabase token paths are told apart by grant_type.
        val path = if (request.url.encodedPath.startsWith("/v1/club/")) {
            assertEquals("ios", request.url.parameters["platform"])
            request.url.encodedPath
        } else {
            request.url.encodedPathAndQuery
        }
        paths += path
        (request.body as? TextContent)?.let { bodies[path] = it.text }
        if (offline) throw IOException("offline")
        return when {
            path == "/auth/v1/token?grant_type=id_token" -> scope.json(signInStatus, tokens())

            path == "/auth/v1/token?grant_type=refresh_token" -> scope.json(refreshStatus, tokens())

            path == "/auth/v1/logout" -> scope.respond("", HttpStatusCode.NoContent).also {
                loggedOut.complete(Unit)
            }

            path == "/v1/club/me" -> {
                authorizations += request.headers[HttpHeaders.Authorization].orEmpty()
                val status = meStatuses.removeFirstOrNull() ?: HttpStatusCode.OK
                if (status == HttpStatusCode.OK) {
                    scope.json(status, json.encodeToString(meAccounts.removeFirstOrNull() ?: ACCOUNT))
                } else {
                    scope.json(status, """{"error":"unauthorized"}""")
                }
            }

            path.startsWith("/v1/club/") -> {
                authorizations += request.headers[HttpHeaders.Authorization].orEmpty()
                scope.json(redeemResponse.first, redeemResponse.second)
            }

            else -> scope.respond("", HttpStatusCode.NotFound)
        }
    }

    private fun tokens() = """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"token_type":"bearer"}"""

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(
        body,
        status,
        headersOf(HttpHeaders.ContentType, "application/json")
    )
}

private class MemoryStore(initial: Map<String, String> = emptyMap()) : SecureStringStore {
    val values = initial.toMutableMap()

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getStringOrNull(key: String): String? = values[key]

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}
