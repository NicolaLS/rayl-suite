package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.widgetspec.WidgetBrand
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInput
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInputType
import xyz.lilsus.raylsuite.core.widgetspec.WidgetProblem
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPurchase as WidgetOffer
import xyz.lilsus.raylsuite.core.widgetspec.WidgetStatusValue

/** A provider widget's size; the provider declares which it supports and what each shows. */
typealias ProviderWidgetSize = xyz.lilsus.raylsuite.core.widgetspec.WidgetFamily

/** Adding a provider widget from a link: fetch, then one setup screen. */
data class ProviderSetupState(
    val url: String,
    val phase: ProviderSetupPhase,
    /** Set when editing a saved widget instead of adding one. */
    val existingWidgetId: String? = null
)

sealed interface ProviderSetupPhase {
    data object Loading : ProviderSetupPhase

    /** The family chooser: swipe through the sizes the provider supports, then pick one. */
    data class Families(
        val definition: WidgetDefinition,
        val body: String,
        val etag: String?,
        val selected: ProviderWidgetSize
    ) : ProviderSetupPhase

    data class Failed(val failure: ProviderSetupFailure) : ProviderSetupPhase

    data class Ready(
        val definition: WidgetDefinition,
        val body: String,
        val etag: String?,
        /** One product for the small single action; the products a larger tile shows. */
        val productIds: List<String>,
        val inputs: Map<String, String>,
        val size: ProviderWidgetSize,
        val validating: Boolean = false,
        val error: ProviderSetupError? = null
    ) : ProviderSetupPhase
}

sealed interface ProviderSetupFailure {
    data object NotResponding : ProviderSetupFailure

    data object Gone : ProviderSetupFailure

    /** Rayl refuses this service; it cannot be added. */
    data object Blocked : ProviderSetupFailure

    /** The link answered, but not with a definition Rayl can use. */
    data class Invalid(val problems: List<WidgetProblem>) : ProviderSetupFailure
}

sealed interface ProviderSetupError {
    data object ChooseProduct : ProviderSetupError

    data class InvalidInput(val inputId: String, val message: String? = null) :
        ProviderSetupError

    /** The provider's read-only check failed for a reason not tied to one input. */
    data class Rejected(val message: String?) : ProviderSetupError

    data object SaveFailed : ProviderSetupError
}

/** One tap target on a provider tile. */
sealed interface ProviderTileAction {
    data class PayProduct(val productId: String, val name: String, val price: StoredAmount) :
        ProviderTileAction

    data class PayAmount(val amount: StoredAmount) : ProviderTileAction

    /** Opens amount entry within the purchase's range. */
    data object EnterAmount : ProviderTileAction

    /** Asks the provider what is due, then confirms. */
    data object Lookup : ProviderTileAction

    /** Opens the full product list to buy something once. */
    data object More : ProviderTileAction
}

/** What a provider widget tile shows, independent of platform layout. */
data class ProviderTile(
    val title: String,
    val providerName: String?,
    /** The provider's icon and tint; only reviewed catalog providers keep theirs. */
    val brand: WidgetBrand?,
    /** The provider's name for the tile at this size, if it chose one. */
    val label: String?,
    val host: String,
    val size: ProviderWidgetSize,
    val purchaseType: String?,
    /** The saved product, or the title for other purchases. */
    val headline: String,
    val price: StoredAmount?,
    val actions: List<ProviderTileAction>,
    val products: List<ProviderTileProduct>,
    val amountRange: xyz.lilsus.raylsuite.core.widgetspec.WidgetAmountRange?,
    val status: ProviderTileStatus?,
    val inputHint: String?,
    /** The saved product is gone or unavailable; the tile must not pay something else. */
    val needsAttention: Boolean,
    /** Rayl blocked this service; the tile offers nothing to pay. */
    val blocked: Boolean,
    val lastPurchase: WidgetPurchase?
)

data class ProviderTileProduct(
    val id: String,
    val name: String,
    val price: StoredAmount,
    val detail: String?,
    val available: Boolean
)

data class ProviderTileStatus(
    val value: WidgetStatusValue?,
    val loading: Boolean,
    /** The last refresh failed; [value] is older than it should be. */
    val stale: Boolean
)

/**
 * Previews show the provider's chosen snapshot. It has no age because it is a sample, not a
 * reading, and it never stands in for a missing value on the Hub.
 */
internal fun WidgetDefinition.snapshotStatus(): ProviderTileStatus? = snapshot?.status?.let {
    ProviderTileStatus(
        value = WidgetStatusValue(
            title = it.title,
            value = it.value,
            typedValue = it.typedValue,
            subtitle = it.subtitle,
            asOf = "",
            observedAt = "",
            refreshAfterSeconds = 0,
            trend = it.trend
        ),
        loading = false,
        stale = false
    )
}

/** A link-added widget stays neutral, so a look-alike cannot borrow a reviewed brand. */
internal fun WidgetDefinition.reviewedBrand(catalog: ServiceCatalog): WidgetBrand? =
    brand?.takeIf { isReviewed(catalog) }

/** Listed in Rayl's catalog and not blocked. */
internal fun WidgetDefinition.isReviewed(catalog: ServiceCatalog): Boolean =
    catalog.entryFor(source.value) != null && !catalog.blocksUrl(source.value)

/** How many items a family's tile shows before "More"; a layout limit, not a provider one. */
internal fun ProviderWidgetSize.tileCapacity(): Int = when (this) {
    ProviderWidgetSize.Small -> 1
    ProviderWidgetSize.Medium -> 4
    ProviderWidgetSize.Large -> 8
}

/**
 * The products a family starts with: the provider's choice. Larger sizes otherwise show what is
 * available, in the provider's order. A small tile is one tap, so it never guesses: without a
 * named product it only has one when the provider sells exactly one, and the user picks otherwise.
 */
internal fun WidgetDefinition.defaultProducts(size: ProviderWidgetSize): List<String> {
    val defaults = families[size]?.products.orEmpty().filter { product(it)?.available == true }
    val available = products.filter { it.available }.map { it.id }
    val fallback = if (size ==
        ProviderWidgetSize.Small
    ) {
        available.takeIf { it.size == 1 }.orEmpty()
    } else {
        available
    }
    return defaults.ifEmpty { fallback }.take(size.tileCapacity())
}

internal fun ProviderWidgetSize.variant(): HubWidgetVariant = when (this) {
    ProviderWidgetSize.Small -> LocalHubWidgets.Single
    ProviderWidgetSize.Medium -> LocalHubWidgets.Row
    ProviderWidgetSize.Large -> LocalHubWidgets.Card
}

internal fun HubWidgetVariant.providerSize(): ProviderWidgetSize = when {
    columns == 1 -> ProviderWidgetSize.Small
    rows == 1 -> ProviderWidgetSize.Medium
    else -> ProviderWidgetSize.Large
}

internal fun HubWidget.providerTile(
    purchases: List<WidgetPurchase>,
    status: ProviderTileStatus?,
    catalog: ServiceCatalog
): ProviderTile? {
    val setup = provider ?: return null
    val definition = setup.definition ?: return null
    val size = variant.providerSize()
    val saved = setup.productIds.firstOrNull()?.let(definition::product)
    val purchase = definition.purchase
    // Only a small widget's single action is a pinned purchase; larger tiles hide what is gone.
    val needsAttention = purchase is WidgetOffer.Product && size == ProviderWidgetSize.Small &&
        (saved == null || !saved.available || setup.pinnedPrices[saved.id] != saved.price)
    val selected = setup.productIds.mapNotNull(definition::product)
    val blocked = catalog.blocksUrl(setup.definitionUrl)
    val actions = if (blocked) {
        emptyList()
    } else {
        when (purchase) {
            is WidgetOffer.Product -> {
                val shown = if (size == ProviderWidgetSize.Small) {
                    listOfNotNull(saved?.takeIf { !needsAttention })
                } else {
                    selected.filter { it.available }.take(size.tileCapacity())
                }
                val more = size != ProviderWidgetSize.Small &&
                    definition.products.count { it.available } > shown.size
                shown.map { ProviderTileAction.PayProduct(it.id, it.name, it.price) } +
                    listOfNotNull(ProviderTileAction.More.takeIf { more })
            }

            is WidgetOffer.Amount -> when (size) {
                // One preset makes a one-tap top-up; otherwise the small tile asks for an amount.
                ProviderWidgetSize.Small -> definition.families[size]?.presets.orEmpty()
                    .filter { it in purchase.range }
                    .singleOrNull()
                    ?.let { listOf(ProviderTileAction.PayAmount(it)) }
                    ?: listOf(ProviderTileAction.EnterAmount)

                else -> definition.families[size]?.presets.orEmpty()
                    .ifEmpty { purchase.range.presets }
                    .filter { it in purchase.range }
                    .take(size.tileCapacity())
                    .map(ProviderTileAction::PayAmount) + ProviderTileAction.EnterAmount
            }

            is WidgetOffer.Lookup -> listOf(ProviderTileAction.Lookup)

            null -> emptyList()
        }
    }
    return ProviderTile(
        title = definition.title,
        providerName = definition.providerName,
        brand = definition.reviewedBrand(catalog),
        label = definition.families[size]?.label,
        host = definition.host,
        size = size,
        purchaseType = purchase?.wireName,
        headline = saved?.name ?: definition.title,
        price = saved?.let { setup.pinnedPrices[it.id] ?: it.price },
        actions = actions,
        products = definition.products.map {
            ProviderTileProduct(
                it.id,
                it.name,
                it.price,
                it.validity ?: it.description,
                it.available
            )
        },
        amountRange = (purchase as? WidgetOffer.Amount)?.range,
        // Until the first reading arrives the tile shows a placeholder, never the snapshot.
        status = (status ?: ProviderTileStatus(null, loading = true, stale = false))
            .takeIf { definition.statusUrl != null },
        // What the provider confirmed about the account first, else the masked phone number.
        inputHint =
            setup.accountHint ?: definition.inputs.firstOrNull { it.type == WidgetInputType.Phone }
                ?.let { setup.inputs[it.id] }?.maskedPhone(),
        needsAttention = needsAttention,
        blocked = blocked,
        lastPurchase = purchases.firstOrNull { it.widgetId == id }
    )
}

/** "+50370162242" → "7016 ···· 42": enough to recognize the number, not to read it. */
internal fun String.maskedPhone(): String {
    val digits = filter(Char::isDigit)
    if (digits.length < 6) return this
    val national = digits.takeLast(8)
    return "${national.take(4)} ···· ${national.takeLast(2)}"
}

/** Normalizes what the user typed into the value the provider receives, or null if invalid. */
internal fun WidgetInput.normalize(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return if (required) null else ""
    if (value.length > 2_048) return null
    return when (type) {
        WidgetInputType.Phone -> {
            val digits = value.filter(Char::isDigit)
            val international = if (value.startsWith("+")) {
                digits
            } else {
                (country?.let(::callingCode) ?: return null) + digits.trimStart('0')
            }
            "+$international".takeIf { international.length in 8..15 }
        }

        WidgetInputType.Number -> value.takeIf { it.all { c -> c.isDigit() || c == '.' } }

        WidgetInputType.Email ->
            value.takeIf { Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(it) }

        WidgetInputType.Text -> value

        WidgetInputType.Choice -> value.takeIf { choice -> options.any { it.value == choice } }
    }
}

/** Calling codes for the countries the first providers serve; others must type "+". */
internal fun callingCode(country: String): String? = when (country) {
    "SV" -> "503"
    "GT" -> "502"
    "HN" -> "504"
    "NI" -> "505"
    "CR" -> "506"
    "PA" -> "507"
    "MX" -> "52"
    "US", "CA" -> "1"
    "ES" -> "34"
    "DE" -> "49"
    "GB" -> "44"
    else -> null
}
