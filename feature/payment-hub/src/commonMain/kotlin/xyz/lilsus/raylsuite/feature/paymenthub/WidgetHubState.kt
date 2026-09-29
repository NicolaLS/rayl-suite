package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount

enum class HubWidgetKind { Contacts, Shortcut, Favorites, Recents, Provider }

enum class HubWidgetScreen { Hub, Gallery, Variants, Configure, ProviderSetup }

data class HubWidgetVariant(val id: String, val columns: Int, val rows: Int, val capacity: Int)

/** Platforms supply each definition's localized copy from its kind. */
data class HubWidgetDefinition(
    val id: String,
    val kind: HubWidgetKind,
    val variants: List<HubWidgetVariant>
)

data class HubWidgetPerson(
    val actionId: String,
    val contactId: String,
    val title: String,
    val address: String,
    val amount: StoredAmount? = null,
    /** What was last paid to this person; context only, a tap still asks for the amount. */
    val lastPaid: StoredAmount? = null
)

data class HubWidgetTile(
    val id: String,
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val people: List<HubWidgetPerson> = emptyList(),
    val provider: ProviderTile? = null
)

data class HubWidgetEditor(
    val definitionId: String,
    val kind: HubWidgetKind,
    val variantId: String,
    val existingWidgetId: String? = null,
    val contactIds: List<String> = emptyList(),
    val amountInput: String = "",
    val currencyCode: String = "USD",
    val comment: String = ""
)

enum class HubWidgetError {
    ContactNameRequired,
    InvalidAddress,
    SelectContacts,
    TooManyContacts,
    InvalidAmount,
    Unavailable,
    SaveFailed
}

data class WidgetHubState(
    val screen: HubWidgetScreen = HubWidgetScreen.Hub,
    val widgets: List<HubWidgetTile> = emptyList(),
    val gallery: List<HubWidgetDefinition> = emptyList(),
    val contacts: List<HubContact> = emptyList(),
    /** Contacts new widgets start with, best first. */
    val defaultContactIds: List<String> = emptyList(),
    val editor: HubWidgetEditor? = null,
    val query: String = "",
    val arranging: Boolean = false,
    val providerSetup: ProviderSetupState? = null,
    val catalog: List<ProviderCatalogItem> = emptyList(),
    /** Rayl's reviewed services and block list, for brands, warnings and blocked tiles. */
    val serviceCatalog: ServiceCatalog = ServiceCatalog(),
    val purchases: List<WidgetPurchase> = emptyList(),
    val busy: Boolean = false,
    val contactSavedSerial: Int = 0,
    val error: HubWidgetError? = null
) {
    val selectedDefinition: HubWidgetDefinition?
        get() = gallery.firstOrNull { it.id == editor?.definitionId }

    val selectedVariant: HubWidgetVariant?
        get() = selectedDefinition?.variants?.firstOrNull { it.id == editor?.variantId }
}

internal fun HubWidgetEditor.shortcutAmount(): StoredAmount? {
    val digits = CurrencyCatalog.infoFor(currencyCode).fractionDigits
    if (amountInput.hasFractionForWholeCurrency(digits)) return null
    val minor = amountInput.parseMinorAmount(digits)?.takeIf { it > 0 } ?: return null
    return StoredAmount(minor, currencyCode)
}

internal fun WidgetHubState.editorValidationError(): HubWidgetError? {
    val editor = editor ?: return HubWidgetError.Unavailable
    val variant = selectedVariant ?: return HubWidgetError.Unavailable
    if (editor.kind == HubWidgetKind.Contacts || editor.kind == HubWidgetKind.Shortcut) {
        if (editor.contactIds.isEmpty()) return HubWidgetError.SelectContacts
        if (editor.contactIds.size > variant.capacity) return HubWidgetError.TooManyContacts
    }
    if (editor.kind == HubWidgetKind.Shortcut && editor.shortcutAmount() == null) {
        return HubWidgetError.InvalidAmount
    }
    return null
}

object LocalHubWidgets {
    val Single = HubWidgetVariant("single", 1, 1, 1)
    val Row = HubWidgetVariant("row", 2, 1, 4)
    val Card = HubWidgetVariant("card", 2, 2, 6)
    val definitions = listOf(
        HubWidgetDefinition("local.contacts", HubWidgetKind.Contacts, listOf(Single, Row, Card)),
        HubWidgetDefinition("local.shortcut", HubWidgetKind.Shortcut, listOf(Single)),
        HubWidgetDefinition("local.favorites", HubWidgetKind.Favorites, listOf(Row, Card)),
        HubWidgetDefinition("local.recents", HubWidgetKind.Recents, listOf(Row, Card))
    )
}
