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
import javax.sql.DataSource

/** One embedded Postgres for the whole test run. */
object TestPostgres {
    val dataSource: DataSource by lazy { EmbeddedPostgres.start().postgresDatabase }

    /** A database with the Club schema and no rows. */
    suspend fun freshDatabase(): ClubDatabase = ClubDatabase(dataSource).also { db ->
        db.applySchema()
        db.transaction {
            createStatement().use { it.execute("truncate club_invite, club_account cascade") }
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
