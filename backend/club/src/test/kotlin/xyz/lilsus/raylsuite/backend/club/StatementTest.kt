package xyz.lilsus.raylsuite.backend.club

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDue
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDueKind
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.ClubMembership
import xyz.lilsus.raylsuite.core.raylapi.ClubStatement
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLine
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLineKind
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementSummary
import xyz.lilsus.raylsuite.core.raylapi.CreditSpendRequest
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

private const val OPERATOR_TOKEN = "operator-secret"

class StatementTest {
    private val treasury = FakeTreasury()

    private class Env(
        val db: ClubDatabase,
        val store: ClubStore,
        val memberships: MembershipStore,
        val credit: CreditStore,
        val statements: StatementStore
    )

    private fun env(db: ClubDatabase) = Env(
        db,
        ClubStore(db),
        MembershipStore(db, { treasury }, { BigDecimal(100_000) }, setOf("ios")),
        CreditStore(db, { treasury }, paused = false, exposureCapSat = 1_000_000),
        StatementStore(db) { treasury }
    )

    private fun statementTest(block: suspend (Env) -> Unit) = runBlocking {
        block(env(TestPostgres.freshDatabase()))
    }

    /** A member of the fee-waived $60 tier (a 60,000-sat line). */
    private suspend fun member(env: Env): UUID {
        val subject = UUID.randomUUID()
        val invite = env.store.createInvite(listOf("member"), true, note = null, expiresAt = null)
        assertIs<RedeemResult.Redeemed>(env.store.redeemInvite(subject, invite.code))
        assertIs<JoinResult.Joined>(env.memberships.join(subject, "member", "ios"))
        return subject
    }

    private suspend fun spend(env: Env, subject: UUID, sats: Long) {
        val request = CreditSpendRequest(
            idempotencyKey = UUID.randomUUID().toString(),
            invoice = treasury.payee(sats * 1_000),
            maxFeeMsat = 0
        )
        assertIs<CreditSpendResult.Spent>(env.credit.spend(subject, request))
    }

    private suspend fun membership(env: Env, subject: UUID): ClubMembership =
        checkNotNull(env.memberships.account(subject, "ios").membership)

    private suspend fun closeNow(env: Env, subject: UUID): ClubStatementSummary {
        val code = env.memberships.account(subject, "ios").memberId
        return assertIs<CloseResult.Closed>(env.statements.closeNow(code)).statement
    }

    /** Requests the repay invoice and pays it, as the treasury's watcher reports it. */
    private suspend fun repay(env: Env, subject: UUID): Long {
        assertEquals(RepayResult.Requested, env.statements.repay(subject))
        val repayment = assertNotNull(membership(env, subject).repayment)
        assertEquals(
            RepaymentPaidResult.Recorded,
            env.statements.repaymentPaid(repayment.invoice)
        )
        return repayment.amountSat
    }

    @Test
    fun monthsCloseAtUtcMonthStartsOnce() = statementTest { env ->
        val subject = member(env)
        spend(env, subject, 1_000)
        val nextMonth = Instant.now().atOffset(ZoneOffset.UTC).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS).plusMonths(1)

        assertEquals(0, env.statements.closeDue(nextMonth.toInstant().minusSeconds(1)))
        assertEquals(1, env.statements.closeDue(nextMonth.toInstant().plusSeconds(60)))
        assertEquals(0, env.statements.closeDue(nextMonth.toInstant().plusSeconds(120)))

        val statement = assertNotNull(membership(env, subject).statement)
        assertEquals(nextMonth.toInstant().toString(), statement.periodEnd)
        assertEquals(nextMonth.plusDays(14).toInstant().toString(), statement.dueAt)
        assertEquals(1_000, statement.closingSat)
        assertEquals(1_000, statement.remainingSat)
        assertEquals(false, statement.paid)

        // A missed month is caught up, and the unpaid balance carries into it.
        val third = nextMonth.plusMonths(2).toInstant()
        assertEquals(2, env.statements.closeDue(third.plusSeconds(60)))
        val carried = assertNotNull(membership(env, subject).statement)
        assertEquals(third.toString(), carried.periodEnd)
        assertEquals(1_000, carried.closingSat)
    }

    @Test
    fun aMonthWithNothingOwedClosesPaid() = statementTest { env ->
        val subject = member(env)
        val statement = closeNow(env, subject)
        assertEquals(0, statement.closingSat)
        assertTrue(statement.paid)
        assertNull(membership(env, subject).amountDue)
        assertEquals(RepayResult.NothingDue, env.statements.repay(subject))
        assertEquals(RepayResult.NotAMember, env.statements.repay(UUID.randomUUID()))
        assertIs<CloseResult.NotAMember>(env.statements.closeNow("NOPE-NOPE"))
    }

    @Test
    fun theStatementIsDueFirstThenTheRestRestoringTheLine() = statementTest { env ->
        val subject = member(env)
        spend(env, subject, 500)
        val closed = closeNow(env, subject)
        assertEquals(500, closed.closingSat)
        spend(env, subject, 200)

        val due = membership(env, subject)
        assertEquals(ClubAmountDue(500, ClubAmountDueKind.STATEMENT, closed.dueAt), due.amountDue)
        assertEquals(700, due.balanceSat)
        assertEquals(60_000 - 700, due.creditAvailableSat)

        assertEquals(500, repay(env, subject))
        val after = membership(env, subject)
        assertTrue(assertNotNull(after.statement).paid)
        assertEquals(0, after.statement?.remainingSat)
        assertEquals(ClubAmountDue(200, ClubAmountDueKind.BALANCE), after.amountDue)
        assertEquals(200, after.balanceSat)
        assertEquals(60_000 - 200, after.creditAvailableSat)
        assertNull(after.repayment)

        assertEquals(200, repay(env, subject))
        assertNull(membership(env, subject).amountDue)
        assertEquals(60_000, membership(env, subject).creditAvailableSat)
        assertEquals(RepayResult.NothingDue, env.statements.repay(subject))
    }

    @Test
    fun oneRepayInvoiceIsOpenAtATimeAndIsRecordedOnce() = statementTest { env ->
        val subject = member(env)
        spend(env, subject, 300)
        assertEquals(RepayResult.Requested, env.statements.repay(subject))
        val first = assertNotNull(membership(env, subject).repayment)
        assertEquals(300, first.amountSat)
        assertEquals(300, treasury.invoices[first.invoice])

        // More spending doesn't replace the open invoice, so nothing can be paid twice.
        spend(env, subject, 100)
        assertEquals(RepayResult.Requested, env.statements.repay(subject))
        assertEquals(first, membership(env, subject).repayment)
        assertEquals(1, treasury.invoices.size)

        // Credit can't repay credit.
        assertEquals(
            CreditQuoteResult.Declined(CreditDecline.OwnInvoice),
            env.credit.quote(subject, first.invoice, null)
        )

        val paid = env.statements.repaymentPaid(first.invoice)
        assertEquals(RepaymentPaidResult.Recorded, paid)
        assertEquals(
            RepaymentPaidResult.AlreadyRecorded,
            env.statements.repaymentPaid(first.invoice)
        )
        assertEquals(RepaymentPaidResult.Unknown, env.statements.repaymentPaid("lnbcrt-other"))
        assertEquals(100, membership(env, subject).balanceSat)

        // The ledger still balances: the treasury got back what it paid out, less the 100 owed.
        val treasuryMsat = env.db.transaction {
            prepareStatement(
                "select coalesce(sum(amount_msat), 0) from club_posting where ledger_account = ?"
            ).use { statement ->
                statement.setString(1, LedgerAccount.TREASURY)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
        assertEquals(-100_000, treasuryMsat)
    }

    @Test
    fun onlyRepaymentsAfterAStatementClosesPayIt() = statementTest { env ->
        val subject = member(env)
        spend(env, subject, 1_000)
        assertEquals(1_000, repay(env, subject))
        spend(env, subject, 300)

        // The earlier repayment is already in the closing balance; it doesn't pay this one.
        val closed = closeNow(env, subject)
        assertEquals(300, closed.closingSat)
        assertEquals(300, closed.remainingSat)
        assertEquals(false, closed.paid)

        // An unpaid statement carries into the next, and paying the latest pays both.
        spend(env, subject, 50)
        val next = closeNow(env, subject)
        assertEquals(350, next.closingSat)
        assertEquals(350, repay(env, subject))
        val paidCount = env.db.transaction {
            prepareStatement("select count(*) from club_statement where paid_at is not null")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }
        assertEquals(2, paidCount)
    }

    @Test
    fun theStatementListsSpendsAndRepayments() = statementTest { env ->
        val subject = member(env)
        spend(env, subject, 500)
        closeNow(env, subject)
        assertEquals(500, repay(env, subject))
        spend(env, subject, 40)

        val statement = assertNotNull(env.statements.statement(subject))
        assertEquals(
            listOf(ClubStatementLineKind.SPEND to 500L),
            statement.lines.map { it.kind to it.amountSat }
        )
        assertEquals(
            listOf(ClubStatementLineKind.REPAYMENT to 500L, ClubStatementLineKind.SPEND to 40L),
            statement.currentLines.map { it.kind to it.amountSat }
        )
        assertEquals(statement.statement?.periodEnd, statement.currentPeriodStart)
        assertNull(env.statements.statement(UUID.randomUUID()))
    }

    @Test
    fun theRoutesRepayShowTheStatementAndLetOperatorsClose() = testApplication {
        val issuer = TestIssuer()
        val db = TestPostgres.freshDatabase()
        val env = env(db)
        application {
            install(ContentNegotiation) { json() }
            club(
                env.store,
                env.memberships,
                env.credit,
                env.statements,
                issuer.verifier,
                OPERATOR_TOKEN
            )
        }
        val subject = member(env)
        val token = issuer.token(subject = subject.toString())

        val nothing = client.post(ClubApi.REPAYMENTS_PATH) { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, nothing.status)
        assertEquals(
            ClubApiError.NOTHING_DUE,
            Json.decodeFromString<RaylApiError>(nothing.bodyAsText()).error
        )

        spend(env, subject, 250)
        val memberCode = env.memberships.account(subject, "ios").memberId
        suspend fun close(authorization: String) = client.post(OPERATOR_CLOSE_STATEMENT_PATH) {
            if (authorization.isNotEmpty()) {
                headers.append(HttpHeaders.Authorization, authorization)
            }
            contentType(ContentType.Application.Json)
            setBody("""{"memberCode":"${memberCode.lowercase()}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, close("Bearer wrong").status)
        val closed = close("Bearer $OPERATOR_TOKEN")
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertEquals(
            250,
            Json.decodeFromString<ClubStatementSummary>(closed.bodyAsText()).closingSat
        )

        val repay = client.post("${ClubApi.REPAYMENTS_PATH}?platform=ios") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, repay.status, repay.bodyAsText())
        val account = Json.decodeFromString<ClubAccount>(repay.bodyAsText())
        assertEquals(250, account.membership?.repayment?.amountSat)
        assertEquals(ClubAmountDueKind.STATEMENT, account.membership?.amountDue?.kind)

        val statement = client.get(ClubApi.STATEMENT_PATH) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, statement.status, statement.bodyAsText())
        val lines = Json.decodeFromString<ClubStatement>(statement.bodyAsText()).lines
        assertEquals(listOf(ClubStatementLineKind.SPEND), lines.map(ClubStatementLine::kind))

        val stranger = issuer.token()
        val notMember = client.get(ClubApi.STATEMENT_PATH) { bearerAuth(stranger) }
        assertEquals(HttpStatusCode.Forbidden, notMember.status)
    }
}
