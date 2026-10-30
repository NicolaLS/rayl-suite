package xyz.lilsus.raylsuite.backend.club

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubInvite

sealed interface RedeemResult {
    data class Redeemed(val account: ClubAccount) : RedeemResult
    data object NotFound : RedeemResult
    data object Used : RedeemResult
    data object Expired : RedeemResult
    data object AlreadyInvited : RedeemResult
}

data class OperatorInvite(
    val code: String,
    val tierCodes: List<String>,
    val feeWaived: Boolean,
    val note: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val redeemedAt: Instant?
)

/** Club accounts and invites in Postgres. */
class ClubStore(private val db: ClubDatabase) {
    /** The account for a Supabase user, created on first use. */
    suspend fun redeemInvite(subject: UUID, typedCode: String): RedeemResult {
        val code = InviteCodes.normalize(typedCode) ?: return RedeemResult.NotFound
        return try {
            db.transaction { redeem(ensureAccount(subject), code) }
        } catch (e: SQLException) {
            // A concurrent redemption by the same account loses on `redeemed_by`'s uniqueness.
            if (e.sqlState == UNIQUE_VIOLATION) RedeemResult.AlreadyInvited else throw e
        }
    }

    suspend fun createInvite(
        tierCodes: List<String>,
        feeWaived: Boolean,
        note: String?,
        expiresAt: Instant?
    ): OperatorInvite {
        require(tierCodes.isNotEmpty() && tierCodes.all(CLUB_TIER_CODES::contains)) {
            "tierCodes must be a non-empty subset of $CLUB_TIER_CODES"
        }
        return db.transaction {
            prepareStatement(
                """
                insert into club_invite (code, tier_codes, fee_waived, note, expires_at)
                values (?, ?, ?, ?, ?)
                returning code, tier_codes, fee_waived, note, created_at, expires_at, redeemed_at
                """
            ).use { statement ->
                statement.setString(1, InviteCodes.generate())
                statement.setArray(2, createArrayOf("text", tierCodes.toTypedArray()))
                statement.setBoolean(3, feeWaived)
                statement.setString(4, note)
                statement.setObject(5, expiresAt?.atOffset(ZoneOffset.UTC))
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.operatorInvite()
                }
            }
        }
    }

    suspend fun invites(): List<OperatorInvite> = db.transaction {
        prepareStatement(
            """
            select code, tier_codes, fee_waived, note, created_at, expires_at, redeemed_at
            from club_invite order by created_at desc
            """
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.operatorInvite()) }
            }
        }
    }

    private fun Connection.redeem(accountId: UUID, code: String): RedeemResult {
        val alreadyInvited = prepareStatement(
            "select 1 from club_invite where redeemed_by = ?"
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.executeQuery().use { it.next() }
        }
        if (alreadyInvited) return RedeemResult.AlreadyInvited

        val state = prepareStatement(
            """
            select redeemed_at is not null as used, coalesce(expires_at <= now(), false) as expired
            from club_invite where code = ? for update
            """
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getBoolean("used") to rows.getBoolean("expired") else null
            }
        } ?: return RedeemResult.NotFound
        val (used, expired) = state
        if (used) return RedeemResult.Used
        if (expired) return RedeemResult.Expired

        prepareStatement(
            "update club_invite set redeemed_by = ?, redeemed_at = now() where code = ?"
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.setString(2, code)
            statement.executeUpdate()
        }
        return RedeemResult.Redeemed(readAccount(accountId))
    }

    private fun ResultSet.operatorInvite(): OperatorInvite {
        @Suppress("UNCHECKED_CAST")
        val tiers = (getArray("tier_codes").array as Array<String>).toList()
        return OperatorInvite(
            code = getString("code"),
            tierCodes = tiers,
            feeWaived = getBoolean("fee_waived"),
            note = getString("note"),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            expiresAt = getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
            redeemedAt = getObject("redeemed_at", OffsetDateTime::class.java)?.toInstant()
        )
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** The account for a Supabase user, created (with a member code) on first use. */
internal fun Connection.ensureAccount(subject: UUID): UUID {
    prepareStatement(
        """
        insert into club_account (id, auth_subject, member_code) values (?, ?, ?)
        on conflict (auth_subject) do nothing
        """
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, subject)
        statement.setString(3, InviteCodes.generate(MEMBER_CODE_LENGTH))
        statement.executeUpdate()
    }
    return prepareStatement("select id from club_account where auth_subject = ?").use { statement ->
        statement.setObject(1, subject)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getObject("id", UUID::class.java)
        }
    }
}

/** The account with its member ID and redeemed invite; the caller adds the membership. */
internal fun Connection.readAccount(id: UUID): ClubAccount = prepareStatement(
    """
    select a.id, a.member_code, i.tier_codes, i.fee_waived
    from club_account a left join club_invite i on i.redeemed_by = a.id
    where a.id = ?
    """
).use { statement ->
    statement.setObject(1, id)
    statement.executeQuery().use { rows ->
        rows.next()
        val invite = rows.getArray("tier_codes")?.let { tiers ->
            @Suppress("UNCHECKED_CAST")
            ClubInvite(
                tierCodes = (tiers.array as Array<String>).toList(),
                feeWaived = rows.getBoolean("fee_waived")
            )
        }
        ClubAccount(
            id = rows.getObject("id", UUID::class.java).toString(),
            memberId = InviteCodes.format(rows.getString("member_code")),
            invite = invite
        )
    }
}

private const val MEMBER_CODE_LENGTH = 10
