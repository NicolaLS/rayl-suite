package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalogEntry
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult

/** What fetching one definition found. */
sealed interface DefinitionCheck {
    data class Valid(val body: String) : DefinitionCheck

    /** The provider answered with something clients would reject, or withdrew the widget. */
    data class Rejected(val reason: String) : DefinitionCheck

    /** Nothing conclusive; the last valid copy stays. */
    data object NotResponding : DefinitionCheck
}

fun interface DefinitionSource {
    suspend fun check(url: String): DefinitionCheck
}

/**
 * Serves the reviewed catalog with each entry's last valid definition, checked by the same parser
 * the app uses. A definition that turns invalid is withdrawn at once; one that is merely
 * unreachable keeps its last valid copy.
 */
class CatalogService(
    private val source: CatalogSource,
    private val definitions: DefinitionSource,
    private val log: (String) -> Unit = ::println,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val valid = ConcurrentHashMap<String, String>()
    private val lastChecks = ConcurrentHashMap<String, Pair<Long, String>>()

    val entryIds: Set<String> = source.entries.mapTo(mutableSetOf()) { it.id }

    suspend fun refresh() = coroutineScope {
        source.entries.map { entry ->
            async {
                val result = when (val check = definitions.check(entry.url)) {
                    is DefinitionCheck.Valid -> {
                        valid[entry.id] = check.body
                        "valid"
                    }

                    is DefinitionCheck.Rejected -> {
                        valid.remove(entry.id)
                        log("${entry.id} rejected: ${check.reason}")
                        "rejected: ${check.reason}"
                    }

                    DefinitionCheck.NotResponding -> {
                        log("${entry.id} not responding")
                        "not responding"
                    }
                }
                lastChecks[entry.id] = clock() to result
            }
        }.awaitAll()
    }

    fun health(): List<EntryHealth> = source.entries.map { entry ->
        val check = lastChecks[entry.id]
        EntryHealth(
            id = entry.id,
            url = entry.url,
            served = valid.containsKey(entry.id),
            lastCheck = check?.first?.let(::isoTime),
            lastResult = check?.second
        )
    }

    fun current(): ServiceCatalog = ServiceCatalog(
        entries = source.entries.map { entry ->
            ServiceCatalogEntry(entry.id, entry.url, entry.section, valid[entry.id])
        },
        blocked = source.blocked
    )
}

/** Fetches like a client would: no cookies, no redirects, and at most 64 KB. */
class HttpDefinitionSource(private val http: HttpClient) : DefinitionSource {
    override suspend fun check(url: String): DefinitionCheck = try {
        val response = http.get(url) { header(HttpHeaders.Accept, "application/json") }
        when (response.status.value) {
            in 200..299 -> {
                val body = response.bodyAsChannel().readBounded(WidgetDefinitionParser.MAX_BYTES)
                if (body == null) {
                    DefinitionCheck.Rejected(
                        "larger than ${WidgetDefinitionParser.MAX_BYTES} bytes"
                    )
                } else {
                    when (val result = WidgetDefinitionParser.parse(url, body)) {
                        is WidgetDefinitionResult.Valid -> DefinitionCheck.Valid(body)

                        is WidgetDefinitionResult.Invalid ->
                            DefinitionCheck.Rejected(
                                result.problems.joinToString {
                                    "${it.field}: ${it.reason}"
                                }
                            )
                    }
                }
            }

            404, 410 -> DefinitionCheck.Rejected("HTTP ${response.status.value}")

            else -> DefinitionCheck.NotResponding
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        DefinitionCheck.NotResponding
    }
}

/** Reads at most [limit] bytes; a longer body is refused rather than truncated. */
internal suspend fun io.ktor.utils.io.ByteReadChannel.readBounded(limit: Int): String? {
    val buffer = ByteArray(limit + 1)
    var size = 0
    while (size <= limit) {
        val read = readAvailable(buffer, size, buffer.size - size)
        if (read == -1) break
        size += read
    }
    return if (size > limit) null else buffer.decodeToString(0, size)
}
