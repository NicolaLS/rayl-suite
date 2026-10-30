package xyz.lilsus.raylsuite.backend.club

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration

/** One embedded Postgres for the whole test run. */
object TestPostgres {
    val dataSource: DataSource by lazy { EmbeddedPostgres.start().postgresDatabase }

    /** A database with the Club schema and no rows. */
    suspend fun freshDatabase(): ClubDatabase = ClubDatabase(dataSource).also { db ->
        db.applySchema()
        db.transaction {
            createStatement().use { it.execute(TRUNCATE_ALL) }
        }
    }
}

/** Signs Supabase-shaped access tokens with a throwaway ES256 key served by [jwks]. */
class TestIssuer(val issuer: String = "https://test-ref.supabase.co/auth/v1") {
    private val keyId = UUID.randomUUID().toString()
    private val keys: KeyPair = newKeyPair()

    val jwks: JwkProvider = JwkProvider { kid ->
        if (kid != keyId) throw SigningKeyNotFoundException(kid, null)
        jwk(keyId, keys.public as ECPublicKey)
    }

    val verifier get() = ClubTokenVerifier(jwks, issuer)

    fun token(
        subject: String = UUID.randomUUID().toString(),
        issuer: String = this.issuer,
        audience: String = "authenticated",
        role: String = "authenticated",
        anonymous: Boolean = false,
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        keys: KeyPair = this.keys
    ): String = JWT.create()
        .withKeyId(keyId)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject(subject)
        .withClaim("role", role)
        .withClaim("is_anonymous", anonymous)
        .withExpiresAt(expiresAt)
        .sign(Algorithm.ECDSA256(keys.public as ECPublicKey, keys.private as ECPrivateKey))

    companion object {
        fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun jwk(keyId: String, key: ECPublicKey): Jwk {
            // A P-256 coordinate as exactly 32 unsigned big-endian bytes.
            fun coordinate(value: java.math.BigInteger): String {
                val bytes = value.toByteArray().takeLast(32).toByteArray()
                val padded = ByteArray(32 - bytes.size) + bytes
                return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
            }
            return Jwk.fromValues(
                mapOf(
                    "kid" to keyId,
                    "kty" to "EC",
                    "alg" to "ES256",
                    "use" to "sig",
                    "crv" to "P-256",
                    "x" to coordinate(key.w.affineX),
                    "y" to coordinate(key.w.affineY)
                )
            )
        }
    }
}

private const val TRUNCATE_ALL =
    "truncate club_posting, club_journal_entry, club_credit_spend, club_fee_quote, " +
        "club_membership, club_invite, club_account"

/**
 * Issues numbered fake invoices and remembers their amounts. Invoices from [payee] can be paid:
 * [nextSend] decides each send's outcome, and [history] is what [sendOutcome] finds later.
 */
class FakeTreasury : Treasury {
    val invoices = mutableMapOf<String, Long>()
    private val payable = mutableMapOf<String, Payable>()
    val sends = mutableListOf<String>()
    val history: MutableMap<String, SendOutcome> = ConcurrentHashMap()
    var nextSend: (SendQuote) -> SendOutcome = { SendOutcome.Settled(it.feeMsat, "00ff", "p") }

    private class Payable(val amountMsat: Long?, val feeMsat: Long, val expired: Boolean)

    /** An invoice someone else issued, payable for [amountMsat] (null: any amount). */
    fun payee(amountMsat: Long?, feeMsat: Long = 0, expired: Boolean = false): String =
        "lnbcrt-payee-${payable.size + 1}".also {
            payable[it] = Payable(amountMsat, feeMsat, expired)
        }

    override suspend fun createInvoice(
        amountSats: Long,
        description: String,
        expiry: Duration
    ): String = "lnbcrt-fake-${invoices.size + 1}".also { invoices[it] = amountSats }

    override suspend fun watchPaidInvoices(onPaid: suspend (invoice: String) -> Unit) = Unit

    override suspend fun quoteSend(invoice: String, amountMsat: Long?): SendQuoteResult {
        val target = payable[invoice] ?: return SendQuoteResult.Invalid
        if (target.expired) return SendQuoteResult.Expired
        val msat = target.amountMsat ?: amountMsat ?: return SendQuoteResult.Invalid
        return SendQuoteResult.Ready(SendQuote(msat, target.feeMsat, handle = invoice))
    }

    override suspend fun send(quote: SendQuote, idempotencyKey: UUID, wait: Duration): SendOutcome {
        val invoice = quote.handle as String
        synchronized(sends) { sends += invoice }
        return nextSend(quote).also { history[invoice] = it }
    }

    override suspend fun sendOutcome(paymentId: String?, invoice: String): SendOutcome? =
        history[invoice]
}
