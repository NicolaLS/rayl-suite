package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalogEntry
import xyz.lilsus.raylsuite.core.raylapi.UsageEvent
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.raylapi.UsageReport
import xyz.lilsus.raylsuite.core.widgetspec.WidgetUrl
import xyz.lilsus.raylsuite.integration.widget.RaylServiceApi

/**
 * The services Rayl recommends and the ones it refuses to pay, from the Rayl backend. The last
 * catalog stays on the device: a backend outage never stops payments, and a blocked service
 * stays blocked offline.
 */
class RaylServices(
    private val api: RaylServiceApi,
    /** Where the last catalog is kept; null keeps it in memory only. */
    private val settings: Settings?,
    private val scope: CoroutineScope,
    /** The user's "Share anonymous usage" setting, read at each event. */
    private val shareUsage: suspend () -> Boolean = { false },
    private val clock: () -> Long = ::platformCurrentTimeMillis
) {
    private val mutableCatalog = MutableStateFlow(load())
    val catalog: StateFlow<ServiceCatalog> = mutableCatalog.asStateFlow()
    private val refreshMutex = Mutex()
    private var fetchedAtMs: Long? = null

    /** Refreshes in the background unless the catalog is younger than [maxAgeMs]. */
    fun refresh(maxAgeMs: Long = REFRESH_AFTER_MS) {
        scope.launch { refreshIfOlderThan(maxAgeMs) }
    }

    /** Waits at most [timeoutMs] for a newer catalog; the last one stays if that fails. */
    suspend fun refreshIfOlderThan(maxAgeMs: Long, timeoutMs: Long = FETCH_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) {
            refreshMutex.withLock {
                val age = fetchedAtMs?.let { clock() - it }
                if (age != null && age < maxAgeMs) return@withLock
                val fetched = api.fetchCatalog() ?: return@withLock
                fetchedAtMs = clock()
                if (fetched != mutableCatalog.value) {
                    mutableCatalog.value = fetched
                    settings?.putString(
                        KEY,
                        json.encodeToString(ServiceCatalog.serializer(), fetched)
                    )
                }
            }
        }
    }

    /** Whether Rayl refuses [definitionUrl], by host or exact URL. */
    fun blocks(definitionUrl: String): Boolean = catalog.value.blocksUrl(definitionUrl)

    /** Counts [event] for a reviewed service; link-added widgets are never reported. */
    fun report(definitionUrl: String, event: UsageEventKind) {
        val entry: ServiceCatalogEntry = catalog.value.entryFor(definitionUrl) ?: return
        scope.launch {
            if (!shareUsage()) return@launch
            api.reportUsage(UsageReport(usagePlatform(), listOf(UsageEvent(entry.id, event))))
        }
    }

    private fun load(): ServiceCatalog = settings?.getStringOrNull(KEY)
        ?.let { runCatching { json.decodeFromString(ServiceCatalog.serializer(), it) }.getOrNull() }
        ?: ServiceCatalog()

    companion object {
        /** How old the catalog may be before a Hub visit or a widget payment asks again. */
        const val REFRESH_AFTER_MS = 5 * 60_000L
        private const val FETCH_TIMEOUT_MS = 5_000L
        private const val KEY = "hub.serviceCatalog"
        private val json = Json { ignoreUnknownKeys = true }

        /** No backend: an empty catalog and nothing reported. */
        fun offline(scope: CoroutineScope) = RaylServices(
            api = object : RaylServiceApi {
                override suspend fun fetchCatalog(): ServiceCatalog? = null

                override suspend fun reportUsage(report: UsageReport) = Unit
            },
            settings = null,
            scope = scope
        )
    }
}

internal fun ServiceCatalog.blocksUrl(definitionUrl: String): Boolean {
    val url = WidgetUrl.parse(definitionUrl) ?: return false
    return blocks(url.host, url.value)
}

/** `android` or `ios`, the only platform detail a usage report carries. */
internal expect fun usagePlatform(): String
