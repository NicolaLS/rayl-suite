package xyz.lilsus.raylsuite.backend.club

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.ClubCreditSpendStatus
import xyz.lilsus.raylsuite.core.raylapi.CreditQuote
import xyz.lilsus.raylsuite.core.raylapi.CreditSpend
import xyz.lilsus.raylsuite.core.raylapi.CreditSpendRequest

/** Why credit can't pay; [code] is the [ClubApiError] the app sees. */
enum class CreditDecline(val code: String) {
    NotAMember(ClubApiError.NOT_A_MEMBER),
    OverLimit(ClubApiError.OVER_LIMIT),
    InvoiceInvalid(ClubApiError.INVOICE_INVALID),
    InvoiceExpired(ClubApiError.INVOICE_EXPIRED),
    OwnInvoice(ClubApiError.OWN_INVOICE),
    FeeChanged(ClubApiError.FEE_CHANGED),

    /** Paused, the exposure cap is reached or unset, or the treasury is down. */
    Unavailable(ClubApiError.UNAVAILABLE)
}

sealed interface CreditQuoteResult {
    data class Quoted(val quote: CreditQuote) : CreditQuoteResult
    data class Declined(val reason: CreditDecline) : CreditQuoteResult
}

sealed interface CreditSpendResult {
    data class Spent(val spend: CreditSpend) : CreditSpendResult
    data class Declined(val reason: CreditDecline) : CreditSpendResult

    /** The idempotency key was already used for another invoice. */
    data object KeyReused : CreditSpendResult
}

/**
 * Club credit spends (D-08): the treasury pays an invoice and the member's line is charged.
 *
 * A spend is reserved against the line and the global exposure cap in one transaction before the
 * treasury sends, so concurrent spends can never overdraw either. A spend whose outcome is unclear
 * stays `paying`, keeping its reservation, until [reconcile] learns the outcome.
 */
class CreditStore(
    private val db: ClubDatabase,
    private val treasury: () -> Treasury?,
    /** The kill switch (D-20). */
    private val paused: Boolean,
    /** Null turns spending off (D-20). */
    private val exposureCapSat: Long?,
    private val sendWait: Duration = 30.seconds
) {
    suspend fun quote(subject: UUID, invoice: String, amountMsat: Long?): CreditQuoteResult {
        val (accountId, quote) = when (val checked = precheck(subject, invoice, amountMsat)) {
            is Prechecked.Ready -> checked.accountId to checked.quote
            is Prechecked.Declined -> return CreditQuoteResult.Declined(checked.reason)
        }
        val room = db.transaction { room(accountId, quote.amountMsat + quote.feeMsat) }
        return room?.let(CreditQuoteResult::Declined)
            ?: CreditQuoteResult.Quoted(CreditQuote(quote.amountMsat, quote.feeMsat))
    }

    suspend fun spend(subject: UUID, request: CreditSpendRequest): CreditSpendResult {
        val accountId = db.transaction { ensureAccount(subject) }
        db.transaction { findSpend(accountId, request.idempotencyKey) }?.let {
            return it.repeatedBy(request)
        }
        val checked = when (
            val result = precheck(subject, request.invoice, request.amountMsat)
        ) {
            is Prechecked.Ready -> result
            is Prechecked.Declined -> return CreditSpendResult.Declined(result.reason)
        }
        val quote = checked.quote
        if (quote.feeMsat > request.maxFeeMsat) {
            return CreditSpendResult.Declined(CreditDecline.FeeChanged)
        }
        val reserved = try {
            db.transaction { reserve(accountId, request, quote) }
        } catch (e: SQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            // A concurrent request with the same key won, or the invoice is already paid.
            return db.transaction { findSpend(accountId, request.idempotencyKey) }
                ?.repeatedBy(request)
                ?: CreditSpendResult.Declined(CreditDecline.InvoiceInvalid)
        }
        val spend = when (reserved) {
            is Reservation.Reserved -> reserved.spend
            is Reservation.Repeated -> return reserved.spend.repeatedBy(request)
            is Reservation.Declined -> return CreditSpendResult.Declined(reserved.reason)
        }
        val outcome = checked.sender.send(quote, spend.id, sendWait)
        return CreditSpendResult.Spent(record(spend.id, outcome).view())
    }

    /**
     * Resolves spends still `paying` from the treasury's payment history. A spend the treasury
     * has no record of stays `paying`: it may still have been submitted.
     */
    suspend fun reconcile(): Int {
        val sender = treasury() ?: return 0
        val paying = db.transaction {
            prepareStatement(
                "select * from club_credit_spend where status = 'paying' order by created_at"
            ).use { statement -> statement.executeQuery().use { it.spends() } }
        }
        var resolved = 0
        paying.forEach { spend ->
            val outcome = sender.sendOutcome(spend.paymentId, spend.invoice) ?: return@forEach
            if (record(spend.id, outcome).status != SpendStatus.Paying) resolved++
        }
        return resolved
    }

    private suspend fun precheck(subject: UUID, invoice: String, amountMsat: Long?): Prechecked {
        if (paused || exposureCapSat == null) return Prechecked.Declined(CreditDecline.Unavailable)
        if (amountMsat != null && amountMsat <= 0) {
            return Prechecked.Declined(CreditDecline.InvoiceInvalid)
        }
        val (accountId, decline) = db.transaction {
            val id = ensureAccount(subject)
            id to when {
                activeLine(id) == null -> CreditDecline.NotAMember
                isOwnInvoice(invoice) -> CreditDecline.OwnInvoice
                else -> null
            }
        }
        if (decline != null) return Prechecked.Declined(decline)
        val sender = treasury() ?: return Prechecked.Declined(CreditDecline.Unavailable)
        val result = try {
            sender.quoteSend(invoice.trim(), amountMsat)
        } catch (_: TreasuryUnavailable) {
            return Prechecked.Declined(CreditDecline.Unavailable)
        }
        return when (result) {
            is SendQuoteResult.Ready -> Prechecked.Ready(accountId, sender, result.quote)
            SendQuoteResult.Invalid -> Prechecked.Declined(CreditDecline.InvoiceInvalid)
            SendQuoteResult.Expired -> Prechecked.Declined(CreditDecline.InvoiceExpired)
        }
    }

    /** Holds the account and the global exposure while it checks room and inserts the spend. */
    private fun Connection.reserve(
        accountId: UUID,
        request: CreditSpendRequest,
        quote: SendQuote
    ): Reservation {
        lockAccount(accountId)
        prepareStatement("select pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, EXPOSURE_LOCK)
            statement.executeQuery().close()
        }
        findSpend(accountId, request.idempotencyKey)?.let { return Reservation.Repeated(it) }
        val line = activeLine(accountId)
            ?: return Reservation.Declined(CreditDecline.NotAMember)
        room(accountId, quote.amountMsat + quote.feeMsat)?.let { return Reservation.Declined(it) }
        val id = UUID.randomUUID()
        prepareStatement(
            """
            insert into club_credit_spend
                (id, account_id, membership_id, idempotency_key, invoice, amount_msat,
                 reserved_fee_msat, status)
            values (?, ?, ?, ?, ?, ?, ?, 'paying')
            """
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, accountId)
            statement.setObject(3, line.membershipId)
            statement.setString(4, request.idempotencyKey)
            statement.setString(5, request.invoice.trim())
            statement.setLong(6, quote.amountMsat)
            statement.setLong(7, quote.feeMsat)
            statement.executeUpdate()
        }
        return Reservation.Reserved(checkNotNull(spendById(id)))
    }

    /** Null if [chargeMsat] more fits the member's line and the global exposure cap. */
    private fun Connection.room(accountId: UUID, chargeMsat: Long): CreditDecline? {
        val line = activeLine(accountId) ?: return CreditDecline.NotAMember
        if (usedMsat(accountId) + chargeMsat > line.limitMsat) return CreditDecline.OverLimit
        val cap = exposureCapSat ?: return CreditDecline.Unavailable
        if (usedMsat(null) + chargeMsat > cap * 1_000) return CreditDecline.Unavailable
        return null
    }

    /**
     * Applies a treasury outcome once: a settled spend moves from reserved to owed in the ledger,
     * a failed one releases its reservation, a pending one keeps it.
     */
    private suspend fun record(spendId: UUID, outcome: SendOutcome): StoredSpend = db.transaction {
        // The account lock orders this entry against statement closes and a concurrent record.
        lockAccount(checkNotNull(spendById(spendId)).accountId)
        val spend = checkNotNull(spendById(spendId))
        if (spend.status != SpendStatus.Paying) return@transaction spend
        when (outcome) {
            is SendOutcome.Settled -> {
                resolve(
                    spendId,
                    SpendStatus.Settled,
                    outcome.paymentId,
                    outcome.feeMsat,
                    outcome.preimage
                )
                val charged = spend.amountMsat + outcome.feeMsat
                recordEntry(
                    kind = "credit_spend",
                    refId = spendId,
                    Posting(LedgerAccount.MEMBER_RECEIVABLE, spend.accountId, charged),
                    Posting(LedgerAccount.TREASURY, null, -charged)
                )
            }

            is SendOutcome.Failed ->
                resolve(spendId, SpendStatus.Failed, outcome.paymentId, null, null)

            is SendOutcome.Pending -> outcome.paymentId?.let { paymentId ->
                prepareStatement(
                    "update club_credit_spend set payment_id = ? where id = ? and payment_id is null"
                ).use { statement ->
                    statement.setString(1, paymentId)
                    statement.setObject(2, spendId)
                    statement.executeUpdate()
                }
            }
        }
        checkNotNull(spendById(spendId))
    }

    private fun Connection.resolve(
        spendId: UUID,
        status: SpendStatus,
        paymentId: String?,
        feeMsat: Long?,
        preimage: String?
    ) {
        prepareStatement(
            """
            update club_credit_spend
            set status = ?, payment_id = coalesce(?, payment_id), fee_msat = ?, preimage = ?,
                resolved_at = now()
            where id = ? and status = 'paying'
            """
        ).use { statement ->
            statement.setString(1, status.dbValue)
            statement.setString(2, paymentId)
            statement.setObject(3, feeMsat, java.sql.Types.BIGINT)
            statement.setString(4, preimage)
            statement.setObject(5, spendId)
            statement.executeUpdate()
        }
    }

    private fun Connection.findSpend(accountId: UUID, idempotencyKey: String): StoredSpend? =
        prepareStatement(
            "select * from club_credit_spend where account_id = ? and idempotency_key = ?"
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.setString(2, idempotencyKey)
            statement.executeQuery().use { it.spends().firstOrNull() }
        }

    private fun Connection.spendById(id: UUID): StoredSpend? =
        prepareStatement("select * from club_credit_spend where id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { it.spends().firstOrNull() }
        }

    /** A fee or repay invoice: the treasury paying itself. */
    private fun Connection.isOwnInvoice(invoice: String): Boolean = prepareStatement(
        """
        select 1 from club_fee_quote where invoice = ?
        union all
        select 1 from club_repayment where invoice = ?
        """
    ).use { statement ->
        statement.setString(1, invoice.trim())
        statement.setString(2, invoice.trim())
        statement.executeQuery().use { it.next() }
    }

    private fun StoredSpend.repeatedBy(request: CreditSpendRequest): CreditSpendResult =
        if (invoice == request.invoice.trim()) {
            CreditSpendResult.Spent(view())
        } else {
            CreditSpendResult.KeyReused
        }

    private sealed interface Prechecked {
        class Ready(val accountId: UUID, val sender: Treasury, val quote: SendQuote) : Prechecked
        data class Declined(val reason: CreditDecline) : Prechecked
    }

    private sealed interface Reservation {
        data class Reserved(val spend: StoredSpend) : Reservation
        data class Repeated(val spend: StoredSpend) : Reservation
        data class Declined(val reason: CreditDecline) : Reservation
    }

    private companion object {
        /** Serializes reservations across all members for the global exposure cap. */
        const val EXPOSURE_LOCK = 0x5241_594c_4352_4544 // "RAYLCRED"
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** The member's active line: its membership and limit. */
internal class ActiveLine(val membershipId: UUID, val limitMsat: Long)

internal fun Connection.activeLine(accountId: UUID): ActiveLine? = prepareStatement(
    """
    select id, credit_limit_msat from club_membership
    where account_id = ? and status = 'active' and credit_limit_msat is not null
    """
).use { statement ->
    statement.setObject(1, accountId)
    statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        ActiveLine(rows.getObject("id", UUID::class.java), rows.getLong("credit_limit_msat"))
    }
}

/**
 * What is owed plus what is reserved for spends in flight, for one account or, when [accountId]
 * is null, for all of them (the Club's exposure).
 */
internal fun Connection.usedMsat(accountId: UUID?): Long = prepareStatement(
    """
    select
        (select coalesce(sum(amount_msat), 0) from club_posting
         where ledger_account = ? and (?::uuid is null or member_account_id = ?::uuid))
      + (select coalesce(sum(amount_msat + reserved_fee_msat), 0) from club_credit_spend
         where status = 'paying' and (?::uuid is null or account_id = ?::uuid))
    """
).use { statement ->
    statement.setString(1, LedgerAccount.MEMBER_RECEIVABLE)
    for (index in 2..5) statement.setObject(index, accountId)
    statement.executeQuery().use { rows ->
        rows.next()
        rows.getLong(1)
    }
}

internal enum class SpendStatus(val dbValue: String, val apiValue: String) {
    Paying("paying", ClubCreditSpendStatus.PENDING),
    Settled("settled", ClubCreditSpendStatus.SETTLED),
    Failed("failed", ClubCreditSpendStatus.FAILED)
}

internal class StoredSpend(
    val id: UUID,
    val accountId: UUID,
    val invoice: String,
    val amountMsat: Long,
    val feeMsat: Long?,
    val status: SpendStatus,
    val paymentId: String?,
    val preimage: String?
) {
    fun view() = CreditSpend(
        id = id.toString(),
        status = status.apiValue,
        amountMsat = amountMsat,
        feeMsat = feeMsat,
        preimage = preimage
    )
}

private fun ResultSet.spends(): List<StoredSpend> = buildList {
    while (next()) {
        add(
            StoredSpend(
                id = getObject("id", UUID::class.java),
                accountId = getObject("account_id", UUID::class.java),
                invoice = getString("invoice"),
                amountMsat = getLong("amount_msat"),
                feeMsat = getObject("fee_msat") as Long?,
                status = SpendStatus.entries.first { it.dbValue == getString("status") },
                paymentId = getString("payment_id"),
                preimage = getString("preimage")
            )
        )
    }
}
