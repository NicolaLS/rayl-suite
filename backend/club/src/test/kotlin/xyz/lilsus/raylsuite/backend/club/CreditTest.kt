package xyz.lilsus.raylsuite.backend.club

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.ClubCreditSpendStatus
import xyz.lilsus.raylsuite.core.raylapi.CreditQuote
import xyz.lilsus.raylsuite.core.raylapi.CreditSpend
import xyz.lilsus.raylsuite.core.raylapi.CreditSpendRequest
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

/** At $100,000 per bitcoin, the $60 Member line is 60,000 sats. */
private const val LINE_MSAT = 60_000_000L

class CreditTest {
    private val treasury = FakeTreasury()

    private class Env(
        val db: ClubDatabase,
        val store: ClubStore,
        val memberships: MembershipStore,
        val credit: CreditStore
    )

    private fun creditTest(
        paused: Boolean = false,
        exposureCapSat: Long? = 1_000_000,
        block: suspend (Env) -> Unit
    ) = runBlocking {
        val db = TestPostgres.freshDatabase()
        val price = BigDecimal(100_000)
        block(
            Env(
                db,
                ClubStore(db),
                MembershipStore(db, { treasury }, { price }, setOf("ios")),
                CreditStore(db, { treasury }, paused, exposureCapSat)
            )
        )
    }

    /** A member of the fee-waived $60 tier. */
    private suspend fun member(env: Env): UUID {
        val subject = UUID.randomUUID()
        val invite = env.store.createInvite(listOf("member"), true, note = null, expiresAt = null)
        assertIs<RedeemResult.Redeemed>(env.store.redeemInvite(subject, invite.code))
        assertIs<JoinResult.Joined>(env.memberships.join(subject, "member", "ios"))
        return subject
    }

    private fun request(
        invoice: String,
        maxFeeMsat: Long = 0,
        key: String = UUID.randomUUID().toString()
    ) = CreditSpendRequest(key, invoice, amountMsat = null, maxFeeMsat = maxFeeMsat)

    private suspend fun available(env: Env, subject: UUID): Long? =
        env.memberships.account(subject, "ios").membership?.creditAvailableSat

    private suspend fun CreditStore.spent(subject: UUID, request: CreditSpendRequest): CreditSpend =
        assertIs<CreditSpendResult.Spent>(spend(subject, request)).spend

    @Test
    fun aSpendChargesTheLineOnceAndRecordsIt() = creditTest { env ->
        val subject = member(env)
        val invoice = treasury.payee(amountMsat = 10_000_000, feeMsat = 5_000)
        assertEquals(
            CreditQuoteResult.Quoted(CreditQuote(10_000_000, 5_000)),
            env.credit.quote(subject, invoice, null)
        )

        val request = request(invoice, maxFeeMsat = 5_000)
        val spend = env.credit.spent(subject, request)
        assertEquals(ClubCreditSpendStatus.SETTLED, spend.status)
        assertEquals(5_000, spend.feeMsat)
        assertEquals(49_995, available(env, subject))
        assertEquals(
            listOf(10_005_000L to "member_receivable", -10_005_000L to "treasury"),
            postings(env.db)
        )

        // The same request answers the same spend without paying again.
        assertEquals(spend, env.credit.spent(subject, request))
        assertEquals(listOf(invoice), treasury.sends)
        assertEquals(
            CreditSpendResult.KeyReused,
            env.credit.spend(subject, request.copy(invoice = treasury.payee(1_000)))
        )
        // An invoice is paid with credit once, whatever the key.
        assertEquals(
            CreditSpendResult.Declined(CreditDecline.InvoiceInvalid),
            env.credit.spend(subject, request(invoice, maxFeeMsat = 5_000))
        )
    }

    @Test
    fun declinesSayWhy() = creditTest { env ->
        val subject = member(env)
        suspend fun decline(invoice: String, maxFeeMsat: Long = 0, who: UUID = subject) =
            assertIs<CreditSpendResult.Declined>(
                env.credit.spend(who, request(invoice, maxFeeMsat))
            ).reason

        assertEquals(
            CreditDecline.NotAMember,
            decline(treasury.payee(1_000), who = UUID.randomUUID())
        )
        assertEquals(CreditDecline.OverLimit, decline(treasury.payee(LINE_MSAT + 1_000)))
        // The fee counts against the line too.
        assertEquals(
            CreditDecline.OverLimit,
            decline(treasury.payee(LINE_MSAT, feeMsat = 1_000), maxFeeMsat = 1_000)
        )
        assertEquals(CreditDecline.InvoiceExpired, decline(treasury.payee(1_000, expired = true)))
        assertEquals(CreditDecline.InvoiceInvalid, decline("lnbcrt-unknown"))
        assertEquals(
            CreditDecline.FeeChanged,
            decline(treasury.payee(1_000, feeMsat = 2_000), 1_000)
        )

        // A Club fee invoice is the treasury's own.
        val joiner = UUID.randomUUID()
        val invite = env.store.createInvite(listOf("member"), false, note = null, expiresAt = null)
        env.store.redeemInvite(joiner, invite.code)
        env.memberships.join(joiner, "member", "ios")
        assertEquals(CreditDecline.OwnInvoice, decline(treasury.invoices.keys.single()))

        assertEquals(LINE_MSAT / 1_000, available(env, subject))
        assertEquals(emptyList(), treasury.sends)
    }

    @Test
    fun theKillSwitchAndAMissingCapTurnSpendingOff() {
        creditTest(paused = true) { env ->
            assertEquals(
                CreditQuoteResult.Declined(CreditDecline.Unavailable),
                env.credit.quote(UUID.randomUUID(), treasury.payee(1_000), null)
            )
            val subject = UUID.randomUUID()
            val invite = env.store.createInvite(
                listOf("member"),
                true,
                note = null,
                expiresAt = null
            )
            env.store.redeemInvite(subject, invite.code)
            val paused =
                MembershipStore(env.db, {
                    treasury
                }, { BigDecimal.ONE }, setOf("ios"), paused = true)
            assertEquals(JoinResult.Unavailable, paused.join(subject, "member", "ios"))
        }
        creditTest(exposureCapSat = null) { env ->
            assertEquals(
                CreditSpendResult.Declined(CreditDecline.Unavailable),
                env.credit.spend(member(env), request(treasury.payee(1_000)))
            )
        }
    }

    @Test
    fun theExposureCapCoversAllMembers() = creditTest(exposureCapSat = 15_000) { env ->
        val first = member(env)
        val second = member(env)
        env.credit.spent(first, request(treasury.payee(10_000_000)))
        assertEquals(
            CreditSpendResult.Declined(CreditDecline.Unavailable),
            env.credit.spend(second, request(treasury.payee(10_000_000)))
        )
        env.credit.spent(second, request(treasury.payee(5_000_000)))
    }

    @Test
    fun concurrentSpendsCannotOverdrawTheLine() = creditTest { env ->
        val subject = member(env)
        val invoices = (1..4).map { treasury.payee(LINE_MSAT / 2) }
        val results = coroutineScope {
            invoices.map { async(Dispatchers.IO) { env.credit.spend(subject, request(it)) } }
                .awaitAll()
        }
        assertEquals(2, results.count { it is CreditSpendResult.Spent })
        assertEquals(
            2,
            results.count { it == CreditSpendResult.Declined(CreditDecline.OverLimit) }
        )
        assertEquals(0, available(env, subject))
    }

    @Test
    fun aFailedSpendReleasesItsReservation() = creditTest { env ->
        val subject = member(env)
        val invoice = treasury.payee(20_000_000)
        treasury.nextSend = { SendOutcome.Failed("p") }
        assertEquals(
            ClubCreditSpendStatus.FAILED,
            env.credit.spent(subject, request(invoice)).status
        )
        assertEquals(LINE_MSAT / 1_000, available(env, subject))
        assertEquals(emptyList(), postings(env.db))

        // Trying the same invoice again is a new spend.
        treasury.nextSend = { SendOutcome.Settled(0, null, "p2") }
        assertEquals(
            ClubCreditSpendStatus.SETTLED,
            env.credit.spent(subject, request(invoice)).status
        )
    }

    @Test
    fun anUnclearSpendKeepsItsReservationUntilReconciled() = creditTest { env ->
        val subject = member(env)
        val invoice = treasury.payee(20_000_000, feeMsat = 1_000)
        treasury.nextSend = { SendOutcome.Pending(null) }
        val request = request(invoice, maxFeeMsat = 1_000)
        assertEquals(ClubCreditSpendStatus.PENDING, env.credit.spent(subject, request).status)
        assertEquals(39_999, available(env, subject))

        // The treasury has no record of it yet: still reserved.
        treasury.history.remove(invoice)
        assertEquals(0, env.credit.reconcile())
        assertEquals(39_999, available(env, subject))

        treasury.history[invoice] =
            SendOutcome.Settled(feeMsat = 800, preimage = "aa", paymentId = "p")
        assertEquals(1, env.credit.reconcile())
        assertEquals(0, env.credit.reconcile())
        val settled = env.credit.spent(subject, request)
        assertEquals(ClubCreditSpendStatus.SETTLED, settled.status)
        assertEquals(800, settled.feeMsat)
        assertEquals(39_999, available(env, subject))
        assertEquals(
            listOf(20_000_800L to "member_receivable", -20_000_800L to "treasury"),
            postings(env.db)
        )
    }

    @Test
    fun theRoutesAnswerSpendsAndDeclines() = testApplication {
        val issuer = TestIssuer()
        val db = TestPostgres.freshDatabase()
        val env = Env(
            db,
            ClubStore(db),
            MembershipStore(db, { treasury }, { BigDecimal(100_000) }, setOf("ios")),
            CreditStore(db, { treasury }, paused = false, exposureCapSat = 1_000_000)
        )
        application {
            install(ContentNegotiation) { json() }
            club(
                env.store,
                env.memberships,
                env.credit,
                StatementStore(db) { treasury },
                issuer.verifier,
                operatorToken = null
            )
        }
        val subject = member(env)
        val token = issuer.token(subject = subject.toString())
        suspend fun post(path: String, body: String) = client.post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val quote = post(ClubApi.CREDIT_QUOTES_PATH, """{"invoice":"${treasury.payee(1_000)}"}""")
        assertEquals(HttpStatusCode.OK, quote.status, quote.bodyAsText())
        assertEquals(CreditQuote(1_000, 0), Json.decodeFromString(quote.bodyAsText()))

        val over = post(
            ClubApi.CREDIT_QUOTES_PATH,
            """{"invoice":"${treasury.payee(LINE_MSAT * 2)}"}"""
        )
        assertEquals(HttpStatusCode.Conflict, over.status)
        assertEquals(
            ClubApiError.OVER_LIMIT,
            Json.decodeFromString<RaylApiError>(over.bodyAsText()).error
        )

        val spend = post(
            ClubApi.CREDIT_SPENDS_PATH,
            """{"idempotencyKey":"k1","invoice":"${treasury.payee(1_000)}","maxFeeMsat":0}"""
        )
        assertEquals(HttpStatusCode.OK, spend.status, spend.bodyAsText())
        assertEquals(
            ClubCreditSpendStatus.SETTLED,
            Json.decodeFromString<CreditSpend>(spend.bodyAsText()).status
        )

        val blank = post(
            ClubApi.CREDIT_SPENDS_PATH,
            """{"idempotencyKey":" ","invoice":"x","maxFeeMsat":0}"""
        )
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    private suspend fun postings(db: ClubDatabase): List<Pair<Long, String>> = db.transaction {
        createStatement().use { statement ->
            statement.executeQuery(
                "select amount_msat, ledger_account from club_posting order by amount_msat desc"
            ).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1) to rows.getString(2))
                }
            }
        }
    }
}
