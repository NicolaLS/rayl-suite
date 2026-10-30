package xyz.lilsus.raylsuite.backend.club

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ClubConfigTest {
    @Test
    fun readsRailwayEnvironment() {
        val config = ClubConfig.fromEnvironment(
            mapOf(
                "DATABASE_URL" to
                    "postgresql://postgres:p%40ss%3Aword@postgres.railway.internal:5432/railway",
                "SUPABASE_URL" to "https://ref.supabase.co/",
                "CLUB_OPERATOR_TOKEN" to "secret"
            )
        )!!
        assertEquals(
            "jdbc:postgresql://postgres.railway.internal:5432/railway",
            config.database.jdbcUrl
        )
        assertEquals("postgres", config.database.user)
        assertEquals("p@ss:word", config.database.password)
        assertEquals("https://ref.supabase.co/auth/v1", config.tokenIssuer)
        assertEquals("https://ref.supabase.co/auth/v1/.well-known/jwks.json", config.jwksUrl)
        assertEquals("secret", config.operatorToken)
        assertFalse("p@ss" in config.database.toString())
    }

    @Test
    fun theClubIsOffUnlessDatabaseAndSupabaseAreSet() {
        assertNull(ClubConfig.fromEnvironment(emptyMap()))
        assertNull(ClubConfig.fromEnvironment(mapOf("DATABASE_URL" to "postgresql://u:p@h/db")))
        assertNull(ClubConfig.fromEnvironment(mapOf("SUPABASE_URL" to "https://ref.supabase.co")))
        val config = ClubConfig.fromEnvironment(
            mapOf(
                "DATABASE_URL" to "postgresql://u:p@h/db?sslmode=require",
                "SUPABASE_URL" to "https://ref.supabase.co",
                "CLUB_OPERATOR_TOKEN" to " "
            )
        )!!
        assertEquals("jdbc:postgresql://h/db?sslmode=require", config.database.jdbcUrl)
        assertNull(config.operatorToken)
    }

    @Test
    fun rejectsNonPostgresUrls() {
        assertFailsWith<IllegalArgumentException> { DatabaseConfig.fromUrl("mysql://u:p@h/db") }
    }
}
