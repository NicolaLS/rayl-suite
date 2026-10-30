package xyz.lilsus.raylsuite.backend.club

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.ClubAccount
import xyz.lilsus.raylsuite.core.raylapi.ClubApi
import xyz.lilsus.raylsuite.core.raylapi.ClubApiError
import xyz.lilsus.raylsuite.core.raylapi.ClubInvite
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

private const val OPERATOR_TOKEN = "operator-test-token"

class ClubRoutesTest {
    private val issuer = TestIssuer()

    private fun clubTest(
        database: ClubDatabase = runBlocking { TestPostgres.freshDatabase() },
        operatorToken: String? = OPERATOR_TOKEN,
        block: suspend ApplicationTestBuilder.(ClubStore) -> Unit
    ) = testApplication {
        val store = ClubStore(database)
        application {
            install(ContentNegotiation) { json() }
            club(store, issuer.verifier, operatorToken)
        }
        block(store)
    }

    @Test
    fun meRequiresAValidSupabaseToken() = clubTest {
        val rejected = listOf(
            null,
            "not-a-jwt",
            issuer.token(issuer = "https://other.supabase.co/auth/v1"),
            issuer.token(audience = "anon"),
            issuer.token(role = "anon"),
            issuer.token(anonymous = true),
            issuer.token(subject = "not-a-uuid"),
            issuer.token(expiresAt = Instant.now().minusSeconds(60)),
            issuer.token(keys = TestIssuer.newKeyPair())
        )
        for (token in rejected) {
            val response = client.get(ClubApi.ME_PATH) { token?.let { bearerAuth(it) } }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "token: $token")
            assertEquals(ClubApiError.UNAUTHORIZED, response.error())
        }
    }

    @Test
    fun meCreatesOneAccountPerSupabaseUser() = clubTest {
        val alice = issuer.token()
        val first = client.get(ClubApi.ME_PATH) { bearerAuth(alice) }.account()
        val second = client.get(ClubApi.ME_PATH) { bearerAuth(alice) }.account()
        val bob = client.get(ClubApi.ME_PATH) { bearerAuth(issuer.token()) }.account()

        assertEquals(first, second)
        assertNull(first.invite)
        assertNotEquals(first.id, bob.id)
    }

    @Test
    fun redeemingAnInviteGrantsItsTiers() = clubTest { store ->
        val invite = store.createInvite(
            listOf("member", "plus"),
            feeWaived = true,
            note = "review",
            expiresAt = null
        )
        val token = issuer.token()

        // Typed casually: lower case, spaces instead of dashes.
        val typed = InviteCodes.format(invite.code).lowercase().replace('-', ' ')
        val redeemed = client.redeem(token, typed)
        assertEquals(HttpStatusCode.OK, redeemed.status)
        assertEquals(
            ClubInvite(listOf("member", "plus"), feeWaived = true),
            redeemed.account().invite
        )
        assertEquals(
            redeemed.account(),
            client.get(ClubApi.ME_PATH) {
                bearerAuth(token)
            }.account()
        )
    }

    @Test
    fun redeemRejectsUnusableInvites() = clubTest { store ->
        val used = store.createInvite(
            listOf("member"),
            feeWaived = false,
            note = null,
            expiresAt = null
        )
        assertEquals(HttpStatusCode.OK, client.redeem(issuer.token(), used.code).status)
        val expired = store.createInvite(
            listOf("member"),
            false,
            null,
            expiresAt = Instant.now().minusSeconds(1)
        )

        val newcomer = issuer.token()
        client.redeem(
            newcomer,
            used.code
        ).assertError(HttpStatusCode.Conflict, ClubApiError.INVITE_USED)
        client.redeem(
            newcomer,
            expired.code
        ).assertError(HttpStatusCode.Gone, ClubApiError.INVITE_EXPIRED)
        client.redeem(
            newcomer,
            "AAAA-BBBB-CCCC"
        ).assertError(HttpStatusCode.NotFound, ClubApiError.INVITE_NOT_FOUND)
        client.redeem(
            newcomer,
            "0000-1111-OOOO"
        ).assertError(HttpStatusCode.NotFound, ClubApiError.INVITE_NOT_FOUND)
        client.redeem(
            newcomer,
            ""
        ).assertError(HttpStatusCode.NotFound, ClubApiError.INVITE_NOT_FOUND)
        assertNull(client.get(ClubApi.ME_PATH) { bearerAuth(newcomer) }.account().invite)
    }

    @Test
    fun anAccountRedeemsAtMostOneInvite() = clubTest { store ->
        val first = store.createInvite(listOf("member"), false, null, null)
        val second = store.createInvite(listOf("plus"), false, null, null)
        val token = issuer.token()

        assertEquals(HttpStatusCode.OK, client.redeem(token, first.code).status)
        client.redeem(
            token,
            second.code
        ).assertError(HttpStatusCode.Conflict, ClubApiError.ALREADY_INVITED)
        assertNull(store.invites().single { it.code == second.code }.redeemedAt)
    }

    @Test
    fun redeemRejectsMalformedBodies() = clubTest {
        val response = client.post(ClubApi.REDEEM_INVITE_PATH) {
            bearerAuth(issuer.token())
            contentType(ContentType.Application.Json)
            setBody("""{"invite":"x"}""")
        }
        response.assertError(HttpStatusCode.BadRequest, ClubApiError.INVALID_REQUEST)
    }

    @Test
    fun operatorsCreateAndListInvites() = clubTest {
        client.post(OPERATOR_INVITES_PATH) { createInviteBody("""{"tierCodes":["member"]}""") }
            .assertError(HttpStatusCode.Unauthorized, ClubApiError.UNAUTHORIZED)
        client.post(OPERATOR_INVITES_PATH) {
            bearerAuth("wrong")
            createInviteBody("""{"tierCodes":["member"]}""")
        }.assertError(HttpStatusCode.Unauthorized, ClubApiError.UNAUTHORIZED)

        for (body in listOf(
            """{"tierCodes":[]}""",
            """{"tierCodes":["gold"]}""",
            """{"tierCodes":["member"],"expiresInDays":0}"""
        )) {
            client.post(OPERATOR_INVITES_PATH) {
                bearerAuth(OPERATOR_TOKEN)
                createInviteBody(body)
            }.assertError(HttpStatusCode.BadRequest, ClubApiError.INVALID_REQUEST)
        }

        val created = client.post(OPERATOR_INVITES_PATH) {
            bearerAuth(OPERATOR_TOKEN)
            createInviteBody(
                """{"tierCodes":["plus","plus"],"feeWaived":true,"note":"App Review","expiresInDays":7}"""
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val invite = Json.decodeFromString<InviteView>(created.bodyAsText())
        assertTrue(
            Regex("[$ALPHABET]{4}-[$ALPHABET]{4}-[$ALPHABET]{4}").matches(invite.code),
            invite.code
        )
        assertEquals(listOf("plus"), invite.tierCodes)
        assertTrue(invite.feeWaived)
        assertNull(invite.redeemedAt)

        assertEquals(HttpStatusCode.OK, client.redeem(issuer.token(), invite.code).status)
        val listed = client.get(OPERATOR_INVITES_PATH) { bearerAuth(OPERATOR_TOKEN) }
        val invites = Json.decodeFromString<List<InviteView>>(listed.bodyAsText())
        assertEquals(invite.code, invites.single().code)
        assertTrue(invites.single().redeemedAt != null)
    }

    @Test
    fun operatorEndpointsAreAbsentWithoutAToken() = clubTest(operatorToken = null) {
        val response = client.get(OPERATOR_INVITES_PATH) { bearerAuth(OPERATOR_TOKEN) }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun anUnreachableDatabaseAnswersUnavailable() {
        val unreachable = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:1/none"
                initializationFailTimeout = -1
                connectionTimeout = 250
            }
        )
        unreachable.use {
            clubTest(database = ClubDatabase(it)) {
                client.get(ClubApi.ME_PATH) { bearerAuth(issuer.token()) }
                    .assertError(HttpStatusCode.ServiceUnavailable, ClubApiError.UNAVAILABLE)
            }
        }
    }

    private suspend fun HttpClient.redeem(token: String, code: String) =
        post(ClubApi.REDEEM_INVITE_PATH) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"code":${Json.encodeToString(code)}}""")
        }

    private fun io.ktor.client.request.HttpRequestBuilder.createInviteBody(json: String) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpResponse.account(): ClubAccount {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return Json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.error(): String =
        Json.decodeFromString<RaylApiError>(bodyAsText()).error

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, error: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(error, error())
    }

    private companion object {
        const val ALPHABET = InviteCodes.ALPHABET
    }
}
