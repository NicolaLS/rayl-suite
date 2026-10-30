package xyz.lilsus.raylsuite.backend.club

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.URI
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration
import kotlinx.serialization.Serializable
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.JoinRequest
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError
import xyz.lilsus.raylsuite.core.raylapi.RedeemInviteRequest

/** Operator endpoints; served only when `CLUB_OPERATOR_TOKEN` is set. Not part of the app contract. */
const val OPERATOR_INVITES_PATH = "/v1/club/operator/invites"

private const val MEMBER_AUTH = "club-member"

/** Verifies Supabase access tokens: signed by a key from [jwks], issued by [issuer]. */
class ClubTokenVerifier(val jwks: JwkProvider, val issuer: String) {
    companion object {
        fun forSupabase(config: ClubConfig) = ClubTokenVerifier(
            jwks = JwkProviderBuilder(URI(config.jwksUrl).toURL())
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build(),
            issuer = config.tokenIssuer
        )
    }
}

/** A signed-in Supabase user. */
data class ClubMember(val subject: UUID)

/**
 * Installs the Club's routes. The host application provides JSON content negotiation and must
 * not install another [Authentication] provider named [MEMBER_AUTH].
 */
fun Application.club(
    store: ClubStore,
    memberships: MembershipStore,
    verifier: ClubTokenVerifier,
    operatorToken: String?
) {
    install(Authentication) {
        jwt(MEMBER_AUTH) {
            verifier(verifier.jwks, verifier.issuer) {
                withAudience("authenticated")
                acceptLeeway(5)
            }
            validate { credential ->
                val payload = credential.payload
                val subject = runCatching { UUID.fromString(payload.subject) }.getOrNull()
                val isAnonymous = payload.getClaim("is_anonymous").asBoolean() == true
                if (subject != null && !isAnonymous &&
                    payload.getClaim("role").asString() == "authenticated"
                ) {
                    ClubMember(subject)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, RaylApiError(ClubApiError.UNAUTHORIZED))
            }
        }
    }
    routing {
        authenticate(MEMBER_AUTH) {
            get(ClubApi.ME_PATH) {
                clubCall { call.respond(memberships.account(member(), platform())) }
            }
            post(ClubApi.MEMBERSHIPS_PATH) {
                val request = runCatching { call.receive<JoinRequest>() }.getOrNull()
                    ?: return@post fail(HttpStatusCode.BadRequest, ClubApiError.INVALID_REQUEST)
                clubCall {
                    when (val result = memberships.join(member(), request.tierCode, platform())) {
                        is JoinResult.Joined -> call.respond(result.account)

                        JoinResult.NotInvited -> fail(
                            HttpStatusCode.Forbidden,
                            ClubApiError.NOT_INVITED
                        )

                        JoinResult.TierNotOffered -> fail(
                            HttpStatusCode.Forbidden,
                            ClubApiError.TIER_NOT_OFFERED
                        )

                        JoinResult.AlreadyMember -> fail(
                            HttpStatusCode.Conflict,
                            ClubApiError.ALREADY_MEMBER
                        )

                        JoinResult.Unavailable -> fail(
                            HttpStatusCode.ServiceUnavailable,
                            ClubApiError.UNAVAILABLE
                        )
                    }
                }
            }
            post(ClubApi.REDEEM_INVITE_PATH) {
                val request = runCatching { call.receive<RedeemInviteRequest>() }.getOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        RaylApiError(ClubApiError.INVALID_REQUEST)
                    )
                clubCall {
                    when (val result = store.redeemInvite(member(), request.code)) {
                        is RedeemResult.Redeemed -> call.respond(
                            memberships.account(member(), platform())
                        )

                        RedeemResult.NotFound -> fail(
                            HttpStatusCode.NotFound,
                            ClubApiError.INVITE_NOT_FOUND
                        )

                        RedeemResult.Used -> fail(HttpStatusCode.Conflict, ClubApiError.INVITE_USED)

                        RedeemResult.Expired -> fail(
                            HttpStatusCode.Gone,
                            ClubApiError.INVITE_EXPIRED
                        )

                        RedeemResult.AlreadyInvited -> fail(
                            HttpStatusCode.Conflict,
                            ClubApiError.ALREADY_INVITED
                        )
                    }
                }
            }
        }
        if (operatorToken != null) {
            route(OPERATOR_INVITES_PATH) { operatorInvites(store, operatorToken) }
        }
    }
}

@Serializable
data class CreateInviteRequest(
    val tierCodes: List<String>,
    val feeWaived: Boolean = false,
    val note: String? = null,
    /** Days until the invite expires; null for no expiry. */
    val expiresInDays: Int? = 30
)

/** An invite as operators see it; [code] is formatted for sharing. */
@Serializable
data class InviteView(
    val code: String,
    val tierCodes: List<String>,
    val feeWaived: Boolean,
    val note: String?,
    val createdAt: String,
    val expiresAt: String?,
    val redeemedAt: String?
)

private fun Route.operatorInvites(store: ClubStore, operatorToken: String) {
    val expected = "Bearer $operatorToken".toByteArray()
    suspend fun RoutingContext.authorized(): Boolean {
        val presented =
            call.request.headers[HttpHeaders.Authorization]?.toByteArray() ?: ByteArray(0)
        if (MessageDigest.isEqual(presented, expected)) return true
        fail(HttpStatusCode.Unauthorized, ClubApiError.UNAUTHORIZED)
        return false
    }
    post {
        if (!authorized()) return@post
        val request = runCatching { call.receive<CreateInviteRequest>() }.getOrNull()
        if (request == null ||
            request.tierCodes.isEmpty() ||
            !request.tierCodes.all(CLUB_TIER_CODES::contains) ||
            (request.expiresInDays != null && request.expiresInDays <= 0)
        ) {
            return@post fail(HttpStatusCode.BadRequest, ClubApiError.INVALID_REQUEST)
        }
        val expiresAt = request.expiresInDays?.let { Instant.now().plus(it.days.toJavaDuration()) }
        clubCall {
            call.respond(
                HttpStatusCode.Created,
                store.createInvite(
                    request.tierCodes.distinct(),
                    request.feeWaived,
                    request.note,
                    expiresAt
                ).view()
            )
        }
    }
    get {
        if (!authorized()) return@get
        clubCall { call.respond(store.invites().map { it.view() }) }
    }
}

private fun OperatorInvite.view() = InviteView(
    code = InviteCodes.format(code),
    tierCodes = tierCodes,
    feeWaived = feeWaived,
    note = note,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt?.toString(),
    redeemedAt = redeemedAt?.toString()
)

private fun RoutingContext.member(): UUID = checkNotNull(call.principal<ClubMember>()).subject

private fun RoutingContext.platform(): String? =
    call.request.queryParameters[ClubApi.PLATFORM_PARAMETER]

private suspend fun RoutingContext.fail(status: HttpStatusCode, error: String) =
    call.respond(status, RaylApiError(error))

/** Answers `club_unavailable` when the database cannot be reached. */
private suspend fun RoutingContext.clubCall(block: suspend RoutingContext.() -> Unit) {
    try {
        block()
    } catch (e: SQLException) {
        call.application.environment.log.warn("Club database error", e)
        fail(HttpStatusCode.ServiceUnavailable, ClubApiError.UNAVAILABLE)
    }
}
