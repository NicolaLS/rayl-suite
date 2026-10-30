package xyz.lilsus.raylsuite.backend.club

import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubFeePayment
import xyz.lilsus.raylsuite.core.raylapi.ClubMembership
import xyz.lilsus.raylsuite.core.raylapi.ClubMembershipStatus
import xyz.lilsus.raylsuite.core.raylapi.ClubTierOffer

sealed interface JoinResult {
    data class Joined(val account: ClubAccount) : JoinResult
    data object NotInvited : JoinResult
    data object TierNotOffered : JoinResult
    data object AlreadyMember : JoinResult

    /** No bitcoin price or no treasury right now. */
    data object Unavailable : JoinResult
}

enum class FeePaidResult {
    Activated,

    /** The same payment was reported before. */
    AlreadyRecorded,

    /** The account was already a member; the fee is kept in the ledger for an operator. */
    Overpaid,

    /** Not a fee invoice. */
    Unknown
}

/** Memberships, their fee quotes, and the ledger entries fees create. */
class MembershipStore(
    private val db: ClubDatabase,
    private val treasury: () -> Treasury?,
    private val btcUsd: () -> BigDecimal?,
    /** Platforms whose apps may offer joining (D-16), e.g. `ios`, `android`. */
    private val joinPlatforms: Set<String>
) {
    /** The account as the app shows it; [platform] decides whether joining is offered. */
    suspend fun account(subject: UUID, platform: String?): ClubAccount = db.transaction {
        memberView(ensureAccount(subject), platform)
    }

    suspend fun join(subject: UUID, tierCode: String, platform: String?): JoinResult {
        val tier = clubTier(tierCode) ?: return JoinResult.TierNotOffered
        val (accountId, invite) = db.transaction {
            val id = ensureAccount(subject)
            id to readAccount(id).invite
        }
        if (invite == null) return JoinResult.NotInvited
        if (tier.code !in invite.tierCodes || platform !in joinPlatforms) {
            return JoinResult.TierNotOffered
        }
        val price = btcUsd()?.takeIf { it.signum() > 0 } ?: return JoinResult.Unavailable

        // The invoice is issued outside the transaction; an unused one simply expires.
        val invoice = if (invite.feeWaived) {
            null
        } else {
            val issuer = treasury() ?: return JoinResult.Unavailable
            try {
                issuer.createInvoice(
                    amountSats = feeSats(tier.feeUsdCents, price),
                    description = "Rayl Club ${tier.code} membership, one year",
                    expiry = QUOTE_LIFETIME
                )
            } catch (_: TreasuryUnavailable) {
                return JoinResult.Unavailable
            }
        }
        return try {
            db.transaction {
                lockAccount(accountId)
                if (hasActiveMembership(accountId)) return@transaction JoinResult.AlreadyMember
                abandonPending(accountId)
                if (invoice == null) {
                    insertMembership(
                        accountId,
                        tier,
                        active = true,
                        limitMsat = lineMsat(tier.lineUsdCents, price)
                    )
                } else {
                    val membership =
                        insertMembership(accountId, tier, active = false, limitMsat = null)
                    insertQuote(
                        membership,
                        feeSats(tier.feeUsdCents, price) * 1_000,
                        price,
                        invoice
                    )
                }
                JoinResult.Joined(memberView(accountId, platform))
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) JoinResult.AlreadyMember else throw e
        }
    }

    /**
     * Records a paid fee invoice once: the ledger takes the fee and the membership becomes active
     * for a year, with the credit limit fixed at the quote's rate.
     */
    suspend fun feePaid(invoice: String): FeePaidResult = db.transaction {
        val quote = prepareStatement(
            """
            select q.id, q.membership_id, q.amount_msat, q.btc_usd, q.paid_at,
                   m.account_id, m.line_usd_cents
            from club_fee_quote q join club_membership m on m.id = q.membership_id
            where q.invoice = ?
            """
        ).use { statement ->
            statement.setString(1, invoice)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@transaction FeePaidResult.Unknown
                PaidQuote(
                    id = rows.getObject("id", UUID::class.java),
                    membershipId = rows.getObject("membership_id", UUID::class.java),
                    amountMsat = rows.getLong("amount_msat"),
                    btcUsd = rows.getBigDecimal("btc_usd"),
                    alreadyPaid = rows.getObject("paid_at") != null,
                    accountId = rows.getObject("account_id", UUID::class.java),
                    lineUsdCents = rows.getLong("line_usd_cents")
                )
            }
        }
        lockAccount(quote.accountId)
        val paid = prepareStatement(
            "update club_fee_quote set paid_at = now() where id = ? and paid_at is null"
        ).use { statement ->
            statement.setObject(1, quote.id)
            statement.executeUpdate() == 1
        }
        if (quote.alreadyPaid || !paid) return@transaction FeePaidResult.AlreadyRecorded
        recordEntry(
            kind = "fee_paid",
            refId = quote.id,
            Posting(LedgerAccount.TREASURY, null, quote.amountMsat),
            Posting(LedgerAccount.FEE_REVENUE, quote.accountId, -quote.amountMsat)
        )
        if (hasActiveMembership(quote.accountId)) return@transaction FeePaidResult.Overpaid
        prepareStatement(
            """
            update club_membership
            set status = 'active', term_start = now(), term_end = now() + interval '1 year',
                credit_limit_msat = ?
            where id = ?
            """
        ).use { statement ->
            statement.setLong(1, lineMsat(quote.lineUsdCents, quote.btcUsd))
            statement.setObject(2, quote.membershipId)
            statement.executeUpdate()
        }
        abandonPending(quote.accountId)
        FeePaidResult.Activated
    }

    private fun Connection.memberView(accountId: UUID, platform: String?): ClubAccount {
        val account = readAccount(accountId)
        val membership = readMembership(accountId)
        val joinInApp = platform in joinPlatforms
        val invite = account.invite
        val offers = if (joinInApp && invite != null &&
            membership?.status != ClubMembershipStatus.ACTIVE
        ) {
            invite.tierCodes.mapNotNull(::clubTier).map { tier ->
                ClubTierOffer(
                    tierCode = tier.code,
                    feeUsd = usdText(tier.feeUsdCents),
                    creditLineUsd = usdText(tier.lineUsdCents),
                    feeWaived = invite.feeWaived
                )
            }
        } else {
            emptyList()
        }
        return account.copy(membership = membership, joinInApp = joinInApp, offers = offers)
    }

    /** The active membership, else the latest pending one with its unexpired payment. */
    private fun Connection.readMembership(accountId: UUID): ClubMembership? = prepareStatement(
        """
        select m.tier_code, m.status, m.credit_limit_msat, m.term_end, m.fee_usd_cents,
               q.invoice, q.amount_msat, q.expires_at
        from club_membership m
        left join lateral (
            select invoice, amount_msat, expires_at from club_fee_quote
            where membership_id = m.id and paid_at is null and expires_at > now()
            order by created_at desc limit 1
        ) q on true
        where m.account_id = ? and m.status in ('active', 'pending_payment')
        order by m.status = 'active' desc, m.created_at desc
        limit 1
        """
    ).use { statement ->
        statement.setObject(1, accountId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return null
            ClubMembership(
                tierCode = rows.getString("tier_code"),
                status = rows.getString("status"),
                creditLimitSat = rows.getObject("credit_limit_msat")?.let { (it as Long) / 1_000 },
                termEnd = rows.getObject(
                    "term_end",
                    OffsetDateTime::class.java
                )?.toInstant()?.toString(),
                payment = rows.getString("invoice")?.let { invoice ->
                    ClubFeePayment(
                        invoice = invoice,
                        amountSat = rows.getLong("amount_msat") / 1_000,
                        feeUsd = usdText(rows.getLong("fee_usd_cents")),
                        expiresAt = rows.getObject(
                            "expires_at",
                            OffsetDateTime::class.java
                        ).toInstant().toString()
                    )
                }
            )
        }
    }

    /** Serializes joins and fee payments per account. */
    private fun Connection.lockAccount(accountId: UUID) {
        prepareStatement("select 1 from club_account where id = ? for update").use { statement ->
            statement.setObject(1, accountId)
            statement.executeQuery().close()
        }
    }

    private fun Connection.hasActiveMembership(accountId: UUID): Boolean = prepareStatement(
        "select 1 from club_membership where account_id = ? and status = 'active'"
    ).use { statement ->
        statement.setObject(1, accountId)
        statement.executeQuery().use { it.next() }
    }

    private fun Connection.abandonPending(accountId: UUID) {
        prepareStatement(
            "update club_membership set status = 'abandoned' where account_id = ? and status = 'pending_payment'"
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertMembership(
        accountId: UUID,
        tier: ClubTier,
        active: Boolean,
        limitMsat: Long?
    ): UUID {
        val id = UUID.randomUUID()
        prepareStatement(
            """
            insert into club_membership
                (id, account_id, tier_code, fee_usd_cents, line_usd_cents, status,
                 term_start, term_end, credit_limit_msat)
            values (?, ?, ?, ?, ?, ?,
                    case when ? then now() end, case when ? then now() + interval '1 year' end, ?)
            """
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, accountId)
            statement.setString(3, tier.code)
            statement.setLong(4, tier.feeUsdCents)
            statement.setLong(5, tier.lineUsdCents)
            statement.setString(6, if (active) "active" else "pending_payment")
            statement.setBoolean(7, active)
            statement.setBoolean(8, active)
            statement.setObject(9, limitMsat, Types.BIGINT)
            statement.executeUpdate()
        }
        return id
    }

    private fun Connection.insertQuote(
        membershipId: UUID,
        amountMsat: Long,
        btcUsd: BigDecimal,
        invoice: String
    ) {
        prepareStatement(
            """
            insert into club_fee_quote (id, membership_id, amount_msat, btc_usd, invoice, expires_at)
            values (?, ?, ?, ?, ?, now() + make_interval(secs => ?))
            """
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, membershipId)
            statement.setLong(3, amountMsat)
            statement.setBigDecimal(4, btcUsd)
            statement.setString(5, invoice)
            statement.setLong(6, QUOTE_LIFETIME.inWholeSeconds)
            statement.executeUpdate()
        }
    }

    private class PaidQuote(
        val id: UUID,
        val membershipId: UUID,
        val amountMsat: Long,
        val btcUsd: BigDecimal,
        val alreadyPaid: Boolean,
        val accountId: UUID,
        val lineUsdCents: Long
    )

    private companion object {
        /** P-01: a fee quote, and its invoice, live ten minutes. */
        val QUOTE_LIFETIME = 10.minutes
        const val UNIQUE_VIOLATION = "23505"
    }
}
