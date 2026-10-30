package xyz.lilsus.raylsuite.backend.club

import java.net.URI
import java.net.URLDecoder

/**
 * The Club's deployment settings. [fromEnvironment] returns null unless both `DATABASE_URL` and
 * `SUPABASE_URL` are set, so the Rayl backend runs without the Club where it is not configured.
 */
data class ClubConfig(
    val database: DatabaseConfig,
    /** The Supabase project URL, e.g. `https://<ref>.supabase.co`. */
    val supabaseUrl: String,
    /** Bearer token for the operator endpoints; they are not served when it is null. */
    val operatorToken: String?
) {
    val tokenIssuer: String get() = "$supabaseUrl/auth/v1"
    val jwksUrl: String get() = "$supabaseUrl/auth/v1/.well-known/jwks.json"

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ClubConfig? {
            val databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() } ?: return null
            val supabaseUrl = env["SUPABASE_URL"]?.takeIf { it.isNotBlank() } ?: return null
            return ClubConfig(
                database = DatabaseConfig.fromUrl(databaseUrl),
                supabaseUrl = supabaseUrl.trimEnd('/'),
                operatorToken = env["CLUB_OPERATOR_TOKEN"]?.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class DatabaseConfig(val jdbcUrl: String, val user: String, val password: String) {
    override fun toString() = "DatabaseConfig(jdbcUrl=$jdbcUrl, user=$user)"

    companion object {
        /** Parses Railway's `postgresql://user:password@host:port/database` form. */
        fun fromUrl(url: String): DatabaseConfig {
            val uri = URI(url)
            require(uri.scheme == "postgresql" || uri.scheme == "postgres") {
                "DATABASE_URL must be a postgresql:// URL"
            }
            val credentials = uri.rawUserInfo?.split(":", limit = 2).orEmpty()
            fun decode(value: String?) =
                value?.let { URLDecoder.decode(it, Charsets.UTF_8) }.orEmpty()
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.rawPath}$query",
                user = decode(credentials.getOrNull(0)),
                password = decode(credentials.getOrNull(1))
            )
        }
    }
}
