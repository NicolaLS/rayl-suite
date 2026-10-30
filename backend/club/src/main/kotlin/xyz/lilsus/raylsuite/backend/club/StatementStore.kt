package xyz.lilsus.raylsuite.backend.club

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDue
import xyz.lilsus.raylsuite.core.raylapi.ClubAmountDueKind
import xyz.lilsus.raylsuite.core.raylapi.ClubRepayment
import xyz.lilsus.raylsuite.core.raylapi.ClubStatement
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLine
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementLineKind
import xyz.lilsus.raylsuite.core.raylapi.ClubStatementSummary

sealed interface RepayResult {
    data object Requested : RepayResult
    data object NotAMember : RepayResult
    data object NothingDue : RepayResult

    /** The treasury can't issue an invoice right now. */
    data object Unavailable : RepayResult
}

enum class RepaymentPaidResult {
    Recorded,

    /** The same payment was reported before. */
    AlreadyRecorded,

    /** Not a repayment invoice. */
    Unknown
}

sealed interface CloseResult {
    data class Closed(val statement: ClubStatementSummary) : CloseResult
    data object NotAMember : CloseResult
}

/**
 * Monthly statements and repayments (D-03): a statement closes each calendar month (UTC, P-03)
 * and is due 14 days later (P-05). The member repays through treasury invoices, at most one open
 * at a time, so they can never pay more than they owe.
 */
class StatementStore(private val db: ClubDatabase, private val treasury: () -> Treasury?) {
    /** Closes every active membership's months that ended before [now]; answers how many. */
    suspend fun closeDue(now: Instant = Instant.now()): Int {
        val lines = db.transaction { activeMemberships() }
        var closed = 0
        lines.forEach { membership ->
            while (true) {
                val closedOne = db.transaction {
                    lockAccount(membership.accountId)
                    val end = monthEnd(periodStart(membership))
                    if (end > now) return@transaction false
                    close(membership, end)
                    true
                }
                if (!closedOne) break
                closed++
            }
        }
        return closed
    }

    /** Closes the open period of the member with [memberCode] now (D-23). */
    suspend fun closeNow(memberCode: String): CloseResult = db.transaction {
        val code = memberCode.uppercase().filterNot { it == '-' || it.isWhitespace() }
        val membership = activeMemberships(code).firstOrNull()
            ?: return@transaction CloseResult.NotAMember
        lockAccount(membership.accountId)
        val end = prepareStatement("select clock_timestamp()").use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }
        close(membership, end)
        CloseResult.Closed(checkNotNull(latestStatement(membership.accountId)).summary())
    }

    /**
     * Makes sure the member has an open repay invoice for what is due: the open one if there is
     * one, otherwise a new one for [amountDue].
     */
    suspend fun repay(subject: UUID): RepayResult {
        val step = db.transaction {
            val id = ensureAccount(subject)
            when {
                activeLine(id) == null -> RepayStep.Done(RepayResult.NotAMember)

                openRepayment(id) != null -> RepayStep.Done(RepayResult.Requested)

                else -> amountDue(id)?.let { RepayStep.Issue(id, it) }
                    ?: RepayStep.Done(RepayResult.NothingDue)
            }
        }
        val (accountId, due) = when (step) {
            is RepayStep.Done -> return step.result
            is RepayStep.Issue -> step
        }
        val issuer = treasury() ?: return RepayResult.Unavailable
        val invoice = try {
            issuer.createInvoice(
                amountSats = due.amountSat,
                description = "Rayl Club repayment",
                expiry = REPAYMENT_LIFETIME
            )
        } catch (_: TreasuryUnavailable) {
            return RepayResult.Unavailable
        }
        db.transaction {
            lockAccount(accountId)
            // A concurrent request may have issued one first; this invoice then goes unused.
            if (openRepayment(accountId) != null) return@transaction
            prepareStatement(
                """
                insert into club_repayment (id, account_id, invoice, amount_msat, expires_at)
                values (?, ?, ?, ?, now() + make_interval(secs => ?))
                """
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, accountId)
                statement.setString(3, invoice)
                statement.setLong(4, due.amountSat * 1_000)
                statement.setLong(5, REPAYMENT_LIFETIME.inWholeSeconds)
                statement.executeUpdate()
            }
        }
        return RepayResult.Requested
    }

    /**
     * Records a paid repay invoice once: the ledger takes the repayment off the member's
     * receivable, and each statement it completes is marked paid.
     */
    suspend fun repaymentPaid(invoice: String): RepaymentPaidResult = db.transaction {
        val (id, accountId, amountMsat) = prepareStatement(
            "select id, account_id, amount_msat from club_repayment where invoice = ?"
        ).use { statement ->
            statement.setString(1, invoice)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@transaction RepaymentPaidResult.Unknown
                Triple(
                    rows.getObject("id", UUID::class.java),
                    rows.getObject("account_id", UUID::class.java),
                    rows.getLong("amount_msat")
                )
            }
        }
        lockAccount(accountId)
        val paid = prepareStatement(
            "update club_repayment set paid_at = now() where id = ? and paid_at is null"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeUpdate() == 1
        }
        if (!paid) return@transaction RepaymentPaidResult.AlreadyRecorded
        recordEntry(
            kind = "repayment",
            refId = id,
            Posting(LedgerAccount.TREASURY, null, amountMsat),
            Posting(LedgerAccount.MEMBER_RECEIVABLE, accountId, -amountMsat)
        )
        markPaid(accountId)
        RepaymentPaidResult.Recorded
    }

    /** The latest statement with its lines and the lines since; null when not a member. */
    suspend fun statement(subject: UUID): ClubStatement? = db.transaction {
        val accountId = ensureAccount(subject)
        val membership = activeMemberships(accountId = accountId).firstOrNull()
            ?: return@transaction null
        val latest = latestStatement(accountId)
        val currentStart = latest?.periodEnd ?: membership.termStart
        ClubStatement(
            statement = latest?.summary(),
            lines = latest?.let { lines(accountId, it.periodStart, it.periodEnd) }.orEmpty(),
            currentPeriodStart = currentStart.toString(),
            currentLines = lines(accountId, currentStart, null)
        )
    }

    /** Records the statement for [membership]'s open period, ending at [end]. */
    private fun Connection.close(membership: OpenMembership, end: Instant) {
        val start = periodStart(membership)
        val closing = receivableMsat(membership.accountId, before = end)
        prepareStatement(
            """
            insert into club_statement
                (id, account_id, membership_id, period_start, period_end, closing_msat, due_at,
                 paid_at)
            values (?, ?, ?, ?, ?, ?, ?, case when ? <= 0 then now() end)
            on conflict (membership_id, period_end) do nothing
            """
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, membership.accountId)
            statement.setObject(3, membership.id)
            statement.setTimestamp(4, Timestamp.from(start))
            statement.setTimestamp(5, Timestamp.from(end))
            statement.setLong(6, closing)
            statement.setTimestamp(7, Timestamp.from(end.plus(DUE_AFTER.toJavaDuration())))
            statement.setLong(8, closing)
            statement.executeUpdate()
        }
    }

    /** Where [membership]'s open period starts: the last statement's end, or the term start. */
    private fun Connection.periodStart(membership: OpenMembership): Instant = prepareStatement(
        "select max(period_end) from club_statement where membership_id = ?"
    ).use { statement ->
        statement.setObject(1, membership.id)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getObject(1, OffsetDateTime::class.java)?.toInstant()
        }
    } ?: membership.termStart

    /** Marks paid every statement of the account that repayments since its close cover. */
    private fun Connection.markPaid(accountId: UUID) {
        prepareStatement(
            """
            update club_statement s set paid_at = clock_timestamp()
            where s.account_id = ? and s.paid_at is null
              and s.closing_msat <= (
                  select coalesce(-sum(p.amount_msat), 0)
                  from club_posting p join club_journal_entry e on e.id = p.entry_id
                  where e.kind = 'repayment' and p.ledger_account = ?
                    and p.member_account_id = s.account_id and e.created_at >= s.period_end
              )
            """
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.setString(2, LedgerAccount.MEMBER_RECEIVABLE)
            statement.executeUpdate()
        }
    }

    private fun Connection.lines(
        accountId: UUID,
        from: Instant,
        until: Instant?
    ): List<ClubStatementLine> = prepareStatement(
        """
        select e.kind, e.created_at, p.amount_msat
        from club_posting p join club_journal_entry e on e.id = p.entry_id
        where p.ledger_account = ? and p.member_account_id = ?
          and e.created_at >= ? and (?::timestamptz is null or e.created_at < ?::timestamptz)
        order by e.created_at
        """
    ).use { statement ->
        statement.setString(1, LedgerAccount.MEMBER_RECEIVABLE)
        statement.setObject(2, accountId)
        statement.setTimestamp(3, Timestamp.from(from))
        statement.setTimestamp(4, until?.let(Timestamp::from))
        statement.setTimestamp(5, until?.let(Timestamp::from))
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val msat = rows.getLong("amount_msat")
                    add(
                        ClubStatementLine(
                            kind = if (msat < 0) {
                                ClubStatementLineKind.REPAYMENT
                            } else {
                                ClubStatementLineKind.SPEND
                            },
                            at = rows.getObject("created_at", OffsetDateTime::class.java)
                                .toInstant().toString(),
                            amountSat = wholeSats(kotlin.math.abs(msat))
                        )
                    )
                }
            }
        }
    }

    private fun Connection.activeMemberships(
        memberCode: String? = null,
        accountId: UUID? = null
    ): List<OpenMembership> = prepareStatement(
        """
        select m.id, m.account_id, m.term_start
        from club_membership m join club_account a on a.id = m.account_id
        where m.status = 'active' and m.term_start is not null
          and (?::text is null or a.member_code = ?::text)
          and (?::uuid is null or m.account_id = ?::uuid)
        """
    ).use { statement ->
        statement.setString(1, memberCode)
        statement.setString(2, memberCode)
        statement.setObject(3, accountId)
        statement.setObject(4, accountId)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        OpenMembership(
                            id = rows.getObject("id", UUID::class.java),
                            accountId = rows.getObject("account_id", UUID::class.java),
                            termStart = rows.getObject("term_start", OffsetDateTime::class.java)
                                .toInstant()
                        )
                    )
                }
            }
        }
    }

    private sealed interface RepayStep {
        data class Done(val result: RepayResult) : RepayStep
        data class Issue(val accountId: UUID, val due: ClubAmountDue) : RepayStep
    }

    private class OpenMembership(val id: UUID, val accountId: UUID, val termStart: Instant)

    private companion object {
        /** P-05: a statement is due 14 days after it closes. */
        val DUE_AFTER = 14.days

        /** Long enough to pay from another wallet; the amount never goes stale in sats. */
        val REPAYMENT_LIFETIME = 1.hours

        /** The first instant of the UTC month after the one [start] falls in. */
        fun monthEnd(start: Instant): Instant = start.atOffset(ZoneOffset.UTC)
            .withDayOfMonth(1).toLocalDate().atStartOfDay().plusMonths(1)
            .toInstant(ZoneOffset.UTC)
    }
}

/** A closed statement as stored. */
internal class StoredStatement(
    val periodStart: Instant,
    val periodEnd: Instant,
    val closingMsat: Long,
    val remainingMsat: Long,
    val dueAt: Instant,
    val paid: Boolean
) {
    fun summary() = ClubStatementSummary(
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        closingSat = wholeSats(closingMsat),
        remainingSat = wholeSats(remainingMsat),
        dueAt = dueAt.toString(),
        paid = paid
    )
}

/** The account's latest closed statement, with what repayments since its close left to pay. */
internal fun Connection.latestStatement(accountId: UUID): StoredStatement? = prepareStatement(
    """
    select s.period_start, s.period_end, s.closing_msat, s.due_at, s.paid_at,
           s.closing_msat - (
               select coalesce(-sum(p.amount_msat), 0)
               from club_posting p join club_journal_entry e on e.id = p.entry_id
               where e.kind = 'repayment' and p.ledger_account = ?
                 and p.member_account_id = s.account_id and e.created_at >= s.period_end
           ) as remaining_msat
    from club_statement s
    where s.account_id = ?
    order by s.period_end desc
    limit 1
    """
).use { statement ->
    statement.setString(1, LedgerAccount.MEMBER_RECEIVABLE)
    statement.setObject(2, accountId)
    statement.executeQuery().use { rows -> if (rows.next()) rows.statement() else null }
}

private fun ResultSet.statement() = StoredStatement(
    periodStart = getObject("period_start", OffsetDateTime::class.java).toInstant(),
    periodEnd = getObject("period_end", OffsetDateTime::class.java).toInstant(),
    closingMsat = getLong("closing_msat"),
    remainingMsat = getLong("remaining_msat").coerceAtLeast(0),
    dueAt = getObject("due_at", OffsetDateTime::class.java).toInstant(),
    paid = getObject("paid_at") != null
)

/** What the member owes for settled spends, from entries written before [before] if given. */
internal fun Connection.receivableMsat(accountId: UUID, before: Instant? = null): Long =
    prepareStatement(
        """
        select coalesce(sum(p.amount_msat), 0)
        from club_posting p join club_journal_entry e on e.id = p.entry_id
        where p.ledger_account = ? and p.member_account_id = ?
          and (?::timestamptz is null or e.created_at < ?::timestamptz)
        """
    ).use { statement ->
        statement.setString(1, LedgerAccount.MEMBER_RECEIVABLE)
        statement.setObject(2, accountId)
        statement.setTimestamp(3, before?.let(Timestamp::from))
        statement.setTimestamp(4, before?.let(Timestamp::from))
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

/**
 * What the repay button pays (D-22): the latest statement's remaining balance while it is unpaid,
 * otherwise everything owed; null when nothing is.
 */
internal fun Connection.amountDue(accountId: UUID): ClubAmountDue? {
    val latest = latestStatement(accountId)
    if (latest != null && latest.remainingMsat > 0) {
        return ClubAmountDue(
            amountSat = wholeSats(latest.remainingMsat),
            kind = ClubAmountDueKind.STATEMENT,
            dueAt = latest.dueAt.toString()
        )
    }
    val owed = receivableMsat(accountId)
    return if (owed > 0) ClubAmountDue(wholeSats(owed), ClubAmountDueKind.BALANCE) else null
}

/** The account's repay invoice that is neither paid nor expired. */
internal fun Connection.openRepayment(accountId: UUID): ClubRepayment? = prepareStatement(
    """
    select invoice, amount_msat, expires_at from club_repayment
    where account_id = ? and paid_at is null and expires_at > now()
    order by created_at desc
    limit 1
    """
).use { statement ->
    statement.setObject(1, accountId)
    statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        ClubRepayment(
            invoice = rows.getString("invoice"),
            amountSat = rows.getLong("amount_msat") / 1_000,
            expiresAt = rows.getObject("expires_at", OffsetDateTime::class.java)
                .toInstant().toString()
        )
    }
}

/** Owed amounts round up, so paying what is shown always settles it. */
internal fun wholeSats(msat: Long): Long = Math.floorDiv(msat + 999, 1_000)
