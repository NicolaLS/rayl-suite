package xyz.lilsus.raylsuite.core.widgetspec

import xyz.lilsus.raylsuite.core.model.StoredAmount

/** A validated rayl-widget/1 definition. Only [WidgetDefinitionParser] creates one. */
data class WidgetDefinition(
    /** Where the definition was fetched from; its origin is the provider's identity. */
    val source: WidgetUrl,
    val specVersion: String,
    val id: String,
    val title: String,
    /** Display attribution only; never proof of who operates the origin. */
    val providerName: String?,
    /** The brand of what is bought, which clients may show for reviewed providers only. */
    val brand: WidgetBrand?,
    val description: String?,
    val inputs: List<WidgetInput>,
    val purchase: WidgetPurchase?,
    val invoiceUrl: WidgetUrl?,
    val deliveryUrl: WidgetUrl?,
    val validateUrl: WidgetUrl?,
    val statusUrl: WidgetUrl?,
    val terms: WidgetTerms?,
    /** The sizes the provider supports, each with what it shows by default. */
    val families: Map<WidgetFamily, WidgetFamilyDefaults>,
    /** What previews show; present whenever [statusUrl] is. */
    val snapshot: WidgetSnapshot?
) {
    /** Supported families from small to large. */
    val supportedFamilies: List<WidgetFamily>
        get() = WidgetFamily.entries.filter { it in families }

    /** The provider identity shown to users. */
    val host: String get() = source.host

    /** What the provider calls itself, falling back to the definition title. */
    val displayName: String get() = providerName ?: title

    val products: List<WidgetProduct>
        get() = (purchase as? WidgetPurchase.Product)?.products.orEmpty()

    fun product(id: String): WidgetProduct? = products.firstOrNull { it.id == id }
}

/** What a definition sells. A definition without one only shows status. */
sealed interface WidgetPurchase {
    val wireName: String

    data class Product(val products: List<WidgetProduct>) : WidgetPurchase {
        override val wireName = "product"
    }

    data class Amount(val range: WidgetAmountRange) : WidgetPurchase {
        override val wireName = "amount"
    }

    /** The provider tells the amount due when asked. */
    data class Lookup(val currencyCode: String) : WidgetPurchase {
        override val wireName = "lookup"
    }
}

data class WidgetProduct(
    val id: String,
    val name: String,
    val price: StoredAmount,
    val description: String?,
    val validity: String?,
    val available: Boolean
)

data class WidgetAmountRange(
    val min: StoredAmount,
    val max: StoredAmount,
    /** Minor units between valid amounts, counted from [min]; null allows every minor unit. */
    val stepMinor: Long?,
    val presets: List<StoredAmount>
) {
    val currencyCode: String get() = min.normalizedCurrencyCode

    operator fun contains(amount: StoredAmount): Boolean =
        amount.normalizedCurrencyCode == currencyCode &&
            amount.minor in min.minor..max.minor &&
            (stepMinor == null || (amount.minor - min.minor) % stepMinor == 0L)
}

enum class WidgetInputType(val wireName: String) {
    Phone("phone"),
    Text("text"),
    Number("number"),
    Email("email"),

    /** Rayl profile: one of a fixed list of [WidgetInput.options], such as a biller. */
    Choice("choice")
}

data class WidgetInput(
    val id: String,
    val type: WidgetInputType,
    val label: String,
    val required: Boolean,
    /** Secret values are concealed and kept only in protected storage. */
    val secret: Boolean,
    val placeholder: String?,
    /** ISO 3166 alpha-2 default country for phone inputs. */
    val country: String?,
    /** What a [WidgetInputType.Choice] input offers; empty for other types. */
    val options: List<WidgetInputOption> = emptyList()
)

data class WidgetInputOption(val value: String, val label: String)

data class WidgetTerms(val url: WidgetUrl, val version: String)

/** Widget sizes, like WidgetKit families: larger families usually offer more. */
enum class WidgetFamily(val wireName: String) {
    Small("small"),
    Medium("medium"),
    Large("large")
}

/**
 * What a family shows until the user changes it: the products on the tile for `product`
 * purchases (one for a small single action), or the preset amounts for `amount` purchases.
 * [label] is the provider's name for the tile at this size, short enough for small tiles.
 */
data class WidgetFamilyDefaults(
    val products: List<String>,
    val presets: List<StoredAmount>,
    val label: String? = null
)

/**
 * Rayl profile: sample content the provider chooses for previews, like a WidgetKit snapshot.
 * Previews never fetch live data, and a snapshot is never shown as a reading on the Hub.
 */
data class WidgetSnapshot(val status: WidgetStatusSample)

/** A status response's display fields, without timing: a sample, not a reading. */
data class WidgetStatusSample(
    val title: String?,
    val value: String,
    val typedValue: WidgetTypedValue?,
    val subtitle: String?,
    val trend: WidgetTrend?
)

/**
 * Brand presentation. The client still draws every tile; the icon is a small square PNG and the
 * tint colours the tile. Invalid values are dropped, never fatal.
 */
data class WidgetBrand(
    /** What the user recognises, such as "PedidosYa", which may differ from the seller. */
    val name: String? = null,
    /** Base64 PNG bytes, without the data URL prefix. */
    val iconPng: String?,
    /** `#RRGGBB`, upper case. */
    val tint: String?
)

/** Rayl profile: recent values, oldest first, drawn as a sparkline next to a status value. */
data class WidgetTrend(val points: List<Double>, val period: String?)
