package xyz.lilsus.raylsuite.backend.club

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Runs JDBC work for the Club on the IO dispatcher, one transaction per call. */
class ClubDatabase(private val dataSource: DataSource) {
    suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
    }

    /** Creates missing tables; safe to run on every start. */
    suspend fun applySchema() = transaction {
        createStatement().use { it.execute(SCHEMA) }
    }

    companion object {
        private val SCHEMA: String =
            checkNotNull(ClubDatabase::class.java.getResource("/club/schema.sql")).readText()

        /**
         * A pool that connects lazily, so an unreachable database never stops the Rayl backend
         * from starting; Club requests fail with `club_unavailable` until it is reachable.
         */
        fun connect(config: DatabaseConfig): ClubDatabase = ClubDatabase(
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.user
                    password = config.password
                    maximumPoolSize = 5
                    initializationFailTimeout = -1
                    connectionTimeout = 5_000
                    poolName = "club"
                }
            )
        )
    }
}

/** Applies the schema, retrying until the database is reachable. */
suspend fun ClubDatabase.applySchemaWhenReachable(onFailure: (SQLException) -> Unit) {
    while (true) {
        try {
            applySchema()
            return
        } catch (e: SQLException) {
            onFailure(e)
            delay(5.seconds)
        }
    }
}
