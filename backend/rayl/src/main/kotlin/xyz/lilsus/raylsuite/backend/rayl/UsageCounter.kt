package xyz.lilsus.raylsuite.backend.rayl

import java.net.URI
import java.net.URLDecoder
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.raylapi.UsageReport

/** One daily counter. Nothing in it identifies a user, a device or a request. */
data class UsageKey(
    val day: LocalDate,
    val entryId: String,
    val event: UsageEventKind,
    val platform: String
)

fun interface UsageSink {
    /** Adds [counts] to the stored totals. Throws when they were not stored. */
    suspend fun add(counts: Map<UsageKey, Long>)
}

/**
 * Counts in memory and flushes totals periodically, so a report never waits on the database and
 * the database never sees single events. Counts that fail to flush are kept for the next try.
 */
class UsageCounter(
    private val knownEntry: (String) -> Boolean,
    private val sink: UsageSink,
    private val clock: () -> Instant = Instant::now,
    private val log: (String) -> Unit = ::println
) {
    private val pending = mutableMapOf<UsageKey, Long>()

    @Volatile private var lastFlush: Instant? = null

    @Volatile private var lastFlushError: String? = null

    /** Unknown entries, platforms and events beyond the limit are dropped, never rejected. */
    fun record(report: UsageReport) {
        if (report.platform !in PLATFORMS) return
        val day = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
        synchronized(pending) {
            report.events.take(RaylApi.MAX_USAGE_EVENTS)
                .filter { knownEntry(it.entryId) }
                .forEach { event ->
                    val key = UsageKey(day, event.entryId, event.event, report.platform)
                    pending[key] = (pending[key] ?: 0) + 1
                }
        }
    }

    suspend fun flush() {
        val counts = synchronized(pending) { pending.toMap().also { pending.clear() } }
        if (counts.isEmpty()) return
        try {
            sink.add(counts)
            lastFlush = clock()
            lastFlushError = null
        } catch (cancellation: CancellationException) {
            restore(counts)
            throw cancellation
        } catch (failure: Exception) {
            restore(counts)
            lastFlushError = failure.message
            log("usage flush failed: ${failure.message}")
        }
    }

    fun health() = UsageHealth(
        pendingCounters = synchronized(pending) { pending.size },
        lastFlush = lastFlush?.toString(),
        lastFlushError = lastFlushError
    )

    private fun restore(counts: Map<UsageKey, Long>) = synchronized(pending) {
        counts.forEach { (key, count) -> pending[key] = (pending[key] ?: 0) + count }
    }

    private companion object {
        val PLATFORMS = setOf("android", "ios")
    }
}

/** Stores daily totals in the `usage_daily` table, creating it on first use. */
class PostgresUsageSink(databaseUrl: String) : UsageSink {
    private val jdbcUrl: String
    private val user: String?
    private val password: String?

    init {
        // Railway provides postgresql://user:password@host:port/database.
        val uri = URI(databaseUrl)
        jdbcUrl =
            "jdbc:postgresql://${uri.host}:${if (uri.port == -1) 5432 else uri.port}${uri.path}"
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        user = credentials.getOrNull(0)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        password = credentials.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    }

    @Volatile private var schemaReady = false

    override suspend fun add(counts: Map<UsageKey, Long>) = withContext(Dispatchers.IO) {
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            if (!schemaReady) {
                connection.createStatement().use { it.execute(CREATE_TABLE) }
                schemaReady = true
            }
            connection.autoCommit = false
            connection.prepareStatement(UPSERT).use { statement ->
                counts.forEach { (key, count) ->
                    statement.setObject(1, key.day)
                    statement.setString(2, key.entryId)
                    statement.setString(3, key.event.name.lowercase())
                    statement.setString(4, key.platform)
                    statement.setLong(5, count)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        }
    }

    private companion object {
        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS usage_daily (
                day date NOT NULL,
                entry_id text NOT NULL,
                event text NOT NULL,
                platform text NOT NULL,
                count bigint NOT NULL,
                PRIMARY KEY (day, entry_id, event, platform)
            )
        """
        const val UPSERT = """
            INSERT INTO usage_daily (day, entry_id, event, platform, count) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (day, entry_id, event, platform)
            DO UPDATE SET count = usage_daily.count + EXCLUDED.count
        """
    }
}

/** Without a database (local runs) totals only go to the log. */
class LogUsageSink(private val log: (String) -> Unit = ::println) : UsageSink {
    override suspend fun add(counts: Map<UsageKey, Long>) {
        counts.forEach { (key, count) -> log("usage $key +$count") }
    }
}
