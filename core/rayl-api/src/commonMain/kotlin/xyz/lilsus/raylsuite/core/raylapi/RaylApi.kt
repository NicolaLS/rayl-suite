package xyz.lilsus.raylsuite.core.raylapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contract between Rayl clients and the Rayl backend (`backend/rayl`). */
object RaylApi {
    const val BITCOIN_PRICES_PATH = "/v1/bitcoin/prices"
    const val CATALOG_PATH = "/v1/catalog"
    const val USAGE_PATH = "/v1/usage"

    /** Upper bound on events per usage request; the backend ignores the rest. */
    const val MAX_USAGE_EVENTS = 20
}

/** Where a build talks to. Debug, E2E and benchmark builds use staging; releases use production. */
enum class RaylBackend(val baseUrl: String) {
    Staging("https://rayl-staging.up.railway.app"),
    Production("https://rayl-production.up.railway.app")
}

/**
 * Bitcoin's price in each fiat currency the backend has a fresh quote for, keyed by upper-case
 * ISO 4217 code. [asOf] is the Unix time in seconds of the oldest source quote that contributed.
 */
@Serializable
data class BitcoinPrices(val asOf: Long, val prices: Map<String, Double>)

/**
 * The services Rayl recommends, plus what Rayl refuses to pay. Removing an entry only delists it;
 * widgets users already added keep working. [blocked] disables widgets and payments everywhere,
 * including widgets added by link.
 */
@Serializable
data class ServiceCatalog(
    val entries: List<ServiceCatalogEntry> = emptyList(),
    /** Hosts (`evil.example`, which also covers its subdomains) or exact definition URLs. */
    val blocked: List<String> = emptyList()
) {
    /** [host] and [url] must be normalized: lowercase host, and the URL as clients store it. */
    fun blocks(host: String, url: String): Boolean = blocked.any { rule ->
        if (rule.startsWith("https://")) rule == url else host == rule || host.endsWith(".$rule")
    }

    fun entryFor(url: String): ServiceCatalogEntry? = entries.firstOrNull { it.url == url }
}

@Serializable
data class ServiceCatalogEntry(
    /** Stable across URL changes; usage counts are keyed by it. */
    val id: String,
    val url: String,
    /** `mobile`, `bills`, `gift_cards`, `info` or `other`; clients treat unknown values as `other`. */
    val section: String,
    /**
     * The last definition the backend fetched and validated, verbatim, so clients can show the
     * entry without contacting the provider. Null until the backend has a valid copy.
     */
    val definition: String? = null
)

/** Anonymous usage: no user, device, amount or input is ever sent. */
@Serializable
data class UsageReport(
    /** `android` or `ios`. */
    val platform: String,
    val events: List<UsageEvent>
)

@Serializable
data class UsageEvent(@SerialName("entry") val entryId: String, val event: UsageEventKind)

@Serializable
enum class UsageEventKind {
    @SerialName("added")
    Added,

    @SerialName("removed")
    Removed,

    @SerialName("paid")
    Paid
}

@Serializable
data class RaylApiError(val error: String)
