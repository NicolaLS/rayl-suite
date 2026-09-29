package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult

/** Where a catalog entry appears in the Add widget sheet. */
enum class ProviderCatalogSection {
    Mobile,
    Bills,
    GiftCards,
    Info,
    OtherServices;

    internal companion object {
        fun fromWire(value: String) = when (value) {
            "mobile" -> Mobile
            "bills" -> Bills
            "gift_cards" -> GiftCards
            "info" -> Info
            else -> OtherServices
        }
    }
}

data class ProviderCatalogEntry(val url: String, val section: ProviderCatalogSection)

/** A catalog entry with its definition, so the sheet can show a live preview. */
data class ProviderCatalogItem(
    val entry: ProviderCatalogEntry,
    val setup: ProviderWidgetSetup?,
    val loading: Boolean
)

/**
 * What the Add widget sheet offers: reviewed entries with a definition the app accepts, from the
 * backend's copy, so browsing never contacts a provider.
 */
internal fun ServiceCatalog.catalogItems(): List<ProviderCatalogItem> =
    entries.mapNotNull { entry ->
        val body = entry.definition ?: return@mapNotNull null
        val result = WidgetDefinitionParser.parse(entry.url, body)
        val definition =
            (result as? WidgetDefinitionResult.Valid)?.definition ?: return@mapNotNull null
        if (blocksUrl(entry.url)) return@mapNotNull null
        val products = definition.defaultProducts(definition.supportedFamilies.first())
            .mapNotNull(definition::product)
        ProviderCatalogItem(
            entry = ProviderCatalogEntry(entry.url, ProviderCatalogSection.fromWire(entry.section)),
            setup = ProviderWidgetSetup(
                definitionUrl = entry.url,
                definitionBody = body,
                productIds = products.map { it.id },
                pinnedPrices = products.associate { it.id to it.price }
            ),
            loading = false
        )
    }
