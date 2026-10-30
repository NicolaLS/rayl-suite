package xyz.lilsus.rayl.providers.rayl.integration.club

import fr.acinq.bitcoin.Crypto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ClubSignInProvider(internal val supabaseName: String) {
    Apple("apple"),
    Google("google")
}

/**
 * An ID token from the platform's Apple or Google sign-in. The platform request carries
 * [ClubNonce.hashed]; Supabase checks it against [ClubNonce.raw].
 */
data class ClubIdToken(val provider: ClubSignInProvider, val idToken: String, val nonce: ClubNonce)

/** A single-use sign-in nonce. */
class ClubNonce internal constructor(val raw: String) {
    /** SHA-256 of [raw] in lower-case hex, as Apple and Google sign-in requests expect. */
    val hashed: String = Crypto.sha256(raw.encodeToByteArray()).toHexString()

    companion object {
        fun generate(): ClubNonce = ClubNonce(secureRandomBytes(32).toHexString())
    }
}

internal expect fun secureRandomBytes(size: Int): ByteArray

/** A Supabase session; [expiresAt] is the access token's expiry in Unix seconds. */
@Serializable
internal data class ClubSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

/** Supabase Auth's REST API, limited to what Rayl Club uses. */
internal class SupabaseAuth(
    private val http: HttpClient,
    private val config: ClubConfig,
    private val now: () -> Long
) {
    suspend fun signIn(token: ClubIdToken): ClubSession = tokenRequest(
        grantType = "id_token",
        body = IdTokenGrant(token.provider.supabaseName, token.idToken, token.nonce.raw),
        rejected = ClubError.SignInFailed
    )

    /** Fails with [ClubError.SessionExpired] when Supabase no longer accepts the refresh token. */
    suspend fun refresh(session: ClubSession): ClubSession = tokenRequest(
        grantType = "refresh_token",
        body = RefreshGrant(session.refreshToken),
        rejected = ClubError.SessionExpired
    )

    /** Revokes the session's refresh tokens; best effort. */
    suspend fun signOut(session: ClubSession) {
        runCatching {
            http.post("${config.supabaseUrl}/auth/v1/logout") {
                header("apikey", config.supabasePublishableKey)
                bearerAuth(session.accessToken)
            }
        }
    }

    private suspend inline fun <reified T : Any> tokenRequest(
        grantType: String,
        body: T,
        rejected: ClubError
    ): ClubSession {
        val response: HttpResponse = clubRequest {
            http.post("${config.supabaseUrl}/auth/v1/token?grant_type=$grantType") {
                header("apikey", config.supabasePublishableKey)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        }
        if (!response.status.isSuccess()) {
            throw ClubException(
                if (response.status.value in REJECTED) rejected else ClubError.Unavailable
            )
        }
        val tokens = clubRequest { json.decodeFromString<TokenResponse>(response.bodyAsText()) }
        return ClubSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt ?: (now() + tokens.expiresIn)
        )
    }

    @Serializable
    private data class IdTokenGrant(
        val provider: String,
        @SerialName("id_token") val idToken: String,
        val nonce: String
    )

    @Serializable
    private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("expires_at") val expiresAt: Long? = null
    )

    private companion object {
        val REJECTED = setOf(
            HttpStatusCode.BadRequest.value,
            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.Forbidden.value,
            HttpStatusCode.UnprocessableEntity.value
        )
    }
}

internal val json = Json { ignoreUnknownKeys = true }
