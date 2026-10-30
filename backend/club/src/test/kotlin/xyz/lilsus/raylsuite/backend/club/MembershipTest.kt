package xyz.lilsus.raylsuite.backend.club

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.raylapi.ClubTierOffer
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

/** At $100,000 per bitcoin, $1 is 1,000 sats: $60 is 60,000 sats, $150 is 150,000 sats. */
private val PRICE = BigDecimal(100_000)

class MembershipTest {
    private val issuer = TestIssuer()
    private val treasury = FakeTreasury()

    private class Env(val store: ClubStore, val memberships: MembershipStore, val db: ClubDatabase)

    private fun membershipTest(
        price: BigDecimal? = PRICE,
        treasuryOnline: Boolean = true,
        joinPlatforms: Set<String> = setOf("ios", "android"),
        block: suspend ApplicationTestBuilder.(Env) -> Unit
    ) = testApplication {
        val db = runBlocking { TestPostgres.freshDatabase() }
        val env = Env(
            ClubStore(db),
            MembershipStore(db, { treasury.takeIf { treasuryOnline } }, { price }, joinPlatforms),
            db
        )
        application {
            install(ContentNegotiation) { json() }
            club(
                env.store,
                env.memberships,
                CreditStore(db, { null }, paused = false, exposureCapSat = null),
                issuer.verifier,
                operatorToken = null
            )
        }
        block(env)
    }

    @Test
    fun invitedMembersSeeTheirOffersOnlyWhereJoiningIsAllowed() = membershipTest(
        joinPlatforms = setOf("ios")
    ) { env ->
        val token = invitedMember(env, listOf("member", "plus"), feeWaived = false)

        val ios = client.me(token, "ios")
        assertTrue(ios.joinInApp)
        assertEquals(
            listOf(
                ClubTierOffer("member", feeUsd = "60", creditLineUsd = "60", feeWaived = false),
                ClubTierOffer("plus", feeUsd = "150", creditLineUsd = "150", feeWaived = false)
            ),
            ios.offers
        )
        assertTrue(Regex("[A-Z2-9]{5}-[A-Z2-9]{5}").matches(ios.memberId), ios.memberId)

        val android = client.me(token, "android")
        assertEquals(false, android.joinInApp)
        assertTrue(android.offers.isEmpty())
        assertEquals(ios.memberId, android.memberId)
    }

    @Test
    fun payingTheFeeActivatesTheMembershipAndRecordsIt() = membershipTest { env ->
        val token = invitedMember(env, listOf("member", "plus"), feeWaived = false)

        val pending = client.join(token, "plus").account()
        val membership = assertNotNull(pending.membership)
        assertEquals(ClubMembershipStatus.PENDING_PAYMENT, membership.status)
        val payment = assertNotNull(membership.payment)
        assertEquals(150_000, payment.amountSat)
        assertEquals("150", payment.feeUsd)
        assertEquals(150_000L, treasury.invoices.getValue(payment.invoice))

        assertEquals(FeePaidResult.Activated, env.memberships.feePaid(payment.invoice))
        assertEquals(FeePaidResult.AlreadyRecorded, env.memberships.feePaid(payment.invoice))

        val active = client.me(token, "ios")
        val activeMembership = assertNotNull(active.membership)
        assertEquals(ClubMembershipStatus.ACTIVE, activeMembership.status)
        assertEquals(150_000, activeMembership.creditLimitSat)
        assertNotNull(activeMembership.termEnd)
        assertNull(activeMembership.payment)
        assertTrue(active.offers.isEmpty())

        assertEquals(
            listOf(150_000_000L to "treasury", -150_000_000L to "fee_revenue"),
            postings(env.db)
        )
        client.join(
            token,
            "member"
        ).assertError(HttpStatusCode.Conflict, ClubApiError.ALREADY_MEMBER)
    }

    @Test
    fun aFeeWaivedInviteActivatesWithoutPayment() = membershipTest { env ->
        val token = invitedMember(env, listOf("member"), feeWaived = true)
        assertEquals(listOf(true), client.me(token, "ios").offers.map { it.feeWaived })

        val membership = assertNotNull(client.join(token, "member").account().membership)
        assertEquals(ClubMembershipStatus.ACTIVE, membership.status)
        assertEquals(60_000, membership.creditLimitSat)
        assertTrue(treasury.invoices.isEmpty())
        assertTrue(postings(env.db).isEmpty())
    }

    @Test
    fun choosingAnotherTierReplacesTheUnpaidOne() = membershipTest { env ->
        val token = invitedMember(env, listOf("member", "plus"), feeWaived = false)
        val first = assertNotNull(client.join(token, "member").account().membership?.payment)
        val second = client.join(token, "plus").account().membership
        assertEquals("plus", second?.tierCode)
        assertNotEquals(first.invoice, second?.payment?.invoice)

        // Paying the abandoned quote still makes the member: the money arrived.
        assertEquals(FeePaidResult.Activated, env.memberships.feePaid(first.invoice))
        assertEquals("member", client.me(token, "ios").membership?.tierCode)
        assertEquals(
            FeePaidResult.Overpaid,
            env.memberships.feePaid(assertNotNull(second?.payment).invoice)
        )
    }

    @Test
    fun joiningNeedsAnInviteForThatTierAndThisPlatform() =
        membershipTest(joinPlatforms = setOf("ios")) { env ->
            val stranger = issuer.token()
            client.join(
                stranger,
                "member"
            ).assertError(HttpStatusCode.Forbidden, ClubApiError.NOT_INVITED)

            val token = invitedMember(env, listOf("member"), feeWaived = false)
            client.join(
                token,
                "plus"
            ).assertError(HttpStatusCode.Forbidden, ClubApiError.TIER_NOT_OFFERED)
            client.join(
                token,
                "gold"
            ).assertError(HttpStatusCode.Forbidden, ClubApiError.TIER_NOT_OFFERED)
            client.join(token, "member", platform = "android")
                .assertError(HttpStatusCode.Forbidden, ClubApiError.TIER_NOT_OFFERED)
            assertEquals(FeePaidResult.Unknown, env.memberships.feePaid("lnbcrt1unknown"))
        }

    @Test
    fun joiningWaitsForAPriceAndTheTreasury() {
        membershipTest(price = null) { env ->
            client.join(invitedMember(env, listOf("member"), false), "member")
                .assertError(HttpStatusCode.ServiceUnavailable, ClubApiError.UNAVAILABLE)
        }
        membershipTest(treasuryOnline = false) { env ->
            client.join(invitedMember(env, listOf("member"), false), "member")
                .assertError(HttpStatusCode.ServiceUnavailable, ClubApiError.UNAVAILABLE)
        }
    }

    @Test
    fun tierPricesConvertAtTheQuoteRate() {
        assertEquals(60_000, feeSats(60_00, PRICE))
        assertEquals(60_000_000, lineMsat(60_00, PRICE))
        // Fees round up to a whole sat; lines round down to a whole msat.
        assertEquals(88_496, feeSats(60_00, BigDecimal(67_800)))
        assertEquals(88_495_575, lineMsat(60_00, BigDecimal(67_800)))
        assertEquals("60.50", usdText(60_50))
    }

    private suspend fun ApplicationTestBuilder.invitedMember(
        env: Env,
        tiers: List<String>,
        feeWaived: Boolean
    ): String {
        val token = issuer.token()
        val invite = env.store.createInvite(tiers, feeWaived, note = null, expiresAt = null)
        val response = client.post(ClubApi.REDEEM_INVITE_PATH) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"${invite.code}"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return token
    }

    private suspend fun HttpClient.me(token: String, platform: String): ClubAccount =
        get("${ClubApi.ME_PATH}?${ClubApi.PLATFORM_PARAMETER}=$platform") {
            bearerAuth(token)
        }.account()

    private suspend fun HttpClient.join(token: String, tier: String, platform: String = "ios") =
        post("${ClubApi.MEMBERSHIPS_PATH}?${ClubApi.PLATFORM_PARAMETER}=$platform") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"tierCode":"$tier"}""")
        }

    private suspend fun HttpResponse.account(): ClubAccount {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, error: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(error, Json.decodeFromString<RaylApiError>(bodyAsText()).error)
    }

    private suspend fun postings(db: ClubDatabase): List<Pair<Long, String>> = db.transaction {
        createStatement().use { statement ->
            statement.executeQuery(
                "select amount_msat, ledger_account from club_posting order by amount_msat desc"
            )
                .use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getLong(1) to rows.getString(2))
                        }
                    }
                }
        }
    }
}
