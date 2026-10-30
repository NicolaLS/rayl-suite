package xyz.lilsus.rayl.providers.rayl.integration.club

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.CreditQuote
import xyz.lilsus.raylsuite.core.raylapi.CreditQuoteRequest
import xyz.lilsus.raylsuite.core.raylapi.CreditSpend
import xyz.lilsus.raylsuite.core.raylapi.CreditSpendRequest
import xyz.lilsus.raylsuite.core.raylapi.JoinRequest
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError
import xyz.lilsus.raylsuite.core.raylapi.RedeemInviteRequest

/** Why a Club action failed, as the screens explain it. */
enum class ClubError {
    /** No connection, or the request did not complete. */
    Network,

    /** The backend or Supabase answered, but could not serve the request now. */
    Unavailable,

    /** Apple or Google sign-in did not produce a session. */
    SignInFailed,

    /** The saved session was revoked or expired; the member signs in again. */
    SessionExpired,
    InviteNotFound,
    InviteUsed,
    InviteExpired,
    AlreadyInvited,
    NotInvited,
    TierNotOffered,
    AlreadyMember
}

internal class ClubException(val error: ClubError) : Exception(error.name)

/** The backend rejected the access token; a refresh may fix it. */
internal class BackendUnauthorized : Exception()

/** The backend declined a credit request; [code] is a [ClubApiError] code, if it sent one. */
internal class CreditDeclined(val code: String?) : Exception(code)

/** Maps transport failures to [ClubError.Network]. */
internal suspend inline fun <T> clubRequest(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ClubException) {
    throw error
} catch (_: Exception) {
    throw ClubException(ClubError.Network)
}

/** The app side of [ClubApi]. */
internal class ClubApiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    /** `ios` or `android`: the backend decides per platform whether joining is offered. */
    private val platform: String
) {
    suspend fun me(accessToken: String): ClubAccount =
        account(clubRequest { http.get(url(ClubApi.ME_PATH)) { bearerAuth(accessToken) } })

    suspend fun redeem(accessToken: String, code: String): ClubAccount = post(
        accessToken,
        ClubApi.REDEEM_INVITE_PATH,
        json.encodeToString(RedeemInviteRequest(code))
    )

    suspend fun join(accessToken: String, tierCode: String): ClubAccount =
        post(accessToken, ClubApi.MEMBERSHIPS_PATH, json.encodeToString(JoinRequest(tierCode)))

    suspend fun creditQuote(accessToken: String, invoice: String, amountMsat: Long?): CreditQuote {
        val response = clubRequest {
            http.post(url(ClubApi.CREDIT_QUOTES_PATH)) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CreditQuoteRequest(invoice, amountMsat)))
            }
        }
        return credit(response)
    }

    /** Waits longer than other calls: the backend waits up to 30 s for the payment to settle. */
    suspend fun creditSpend(accessToken: String, request: CreditSpendRequest): CreditSpend {
        val response = clubRequest {
            http.post(url(ClubApi.CREDIT_SPENDS_PATH)) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
                timeout {
                    requestTimeoutMillis = SPEND_TIMEOUT_MILLIS
                    socketTimeoutMillis = SPEND_TIMEOUT_MILLIS
                }
            }
        }
        return credit(response)
    }

    private suspend inline fun <reified T> credit(response: HttpResponse): T {
        val body = clubRequest { response.bodyAsText() }
        if (response.status.isSuccess()) return clubRequest { json.decodeFromString<T>(body) }
        if (response.status == HttpStatusCode.Unauthorized) throw BackendUnauthorized()
        throw CreditDeclined(
            runCatching {
                json.decodeFromString<RaylApiError>(body).error
            }.getOrNull()
        )
    }

    private fun url(path: String) = "$baseUrl$path?${ClubApi.PLATFORM_PARAMETER}=$platform"

    private suspend fun post(accessToken: String, path: String, body: String): ClubAccount =
        account(
            clubRequest {
                http.post(url(path)) {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        )

    private suspend fun account(response: HttpResponse): ClubAccount {
        val body = clubRequest { response.bodyAsText() }
        if (response.status.isSuccess()) {
            return clubRequest { json.decodeFromString<ClubAccount>(body) }
        }
        if (response.status == HttpStatusCode.Unauthorized) throw BackendUnauthorized()
        val code = runCatching { json.decodeFromString<RaylApiError>(body).error }.getOrNull()
        throw ClubException(
            when (code) {
                ClubApiError.INVITE_NOT_FOUND -> ClubError.InviteNotFound
                ClubApiError.INVITE_USED -> ClubError.InviteUsed
                ClubApiError.INVITE_EXPIRED -> ClubError.InviteExpired
                ClubApiError.ALREADY_INVITED -> ClubError.AlreadyInvited
                ClubApiError.NOT_INVITED -> ClubError.NotInvited
                ClubApiError.TIER_NOT_OFFERED -> ClubError.TierNotOffered
                ClubApiError.ALREADY_MEMBER -> ClubError.AlreadyMember
                else -> ClubError.Unavailable
            }
        )
    }

    private companion object {
        const val SPEND_TIMEOUT_MILLIS = 45_000L
    }
}
