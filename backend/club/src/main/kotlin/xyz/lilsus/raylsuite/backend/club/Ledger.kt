package xyz.lilsus.raylsuite.backend.club

import java.sql.Connection
import java.util.UUID

/** Ledger accounts. Positive amounts debit (assets grow), negative amounts credit. */
object LedgerAccount {
    /** The treasury wallet's sats. */
    const val TREASURY = "treasury"

    /** Annual fees earned; postings name the member who paid. */
    const val FEE_REVENUE = "fee_revenue"

    /** What each member owes for credit spends; postings name the member. */
    const val MEMBER_RECEIVABLE = "member_receivable"
}

class Posting(val ledgerAccount: String, val memberAccountId: UUID?, val amountMsat: Long)

/**
 * Appends one balanced journal entry. An entry for the same [kind] and [refId] already recorded
 * makes the insert fail on its unique key, so callers record each event at most once.
 */
internal fun Connection.recordEntry(kind: String, refId: UUID, vararg postings: Posting) {
    require(postings.size >= 2 && postings.sumOf { it.amountMsat } == 0L) {
        "A journal entry needs balanced postings"
    }
    val entryId = UUID.randomUUID()
    prepareStatement("insert into club_journal_entry (id, kind, ref_id) values (?, ?, ?)").use {
        it.setObject(1, entryId)
        it.setString(2, kind)
        it.setObject(3, refId)
        it.executeUpdate()
    }
    prepareStatement(
        "insert into club_posting (entry_id, ledger_account, member_account_id, amount_msat) values (?, ?, ?, ?)"
    ).use { statement ->
        postings.forEach { posting ->
            statement.setObject(1, entryId)
            statement.setString(2, posting.ledgerAccount)
            statement.setObject(3, posting.memberAccountId)
            statement.setLong(4, posting.amountMsat)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}
