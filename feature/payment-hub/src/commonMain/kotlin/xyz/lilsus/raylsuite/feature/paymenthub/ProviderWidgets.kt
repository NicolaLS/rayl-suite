package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryItem
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryState

/**
 * A third-party widget as the user set it up. The definition body is a snapshot so the tile can
 * be drawn offline; every payment revalidates it against the provider first.
 */
data class ProviderWidgetSetup(
    val definitionUrl: String,
    val definitionBody: String,
    val etag: String? = null,
    /** One product for `fixed`, an ordered selection for `choose`, none otherwise. */
    val productIds: List<String> = emptyList(),
    /** Prices the user agreed to, in each product's own currency. */
    val pinnedPrices: Map<String, StoredAmount> = emptyMap(),
    val inputs: Map<String, String> = emptyMap(),
    val acceptedTermsVersion: String? = null,
    /** What validation said about the inputs, such as a masked account holder. */
    val accountHint: String? = null
) {
    val definition: WidgetDefinition? by lazy {
        (
            WidgetDefinitionParser.parse(
                definitionUrl,
                definitionBody
            ) as? WidgetDefinitionResult.Valid
            )
            ?.definition
    }
}

/** A paid provider order. Kept on the device so delivered items survive restarts. */
data class WidgetPurchase(
    val id: String,
    val widgetId: String?,
    val definitionUrl: String,
    /** The spec's widget `id`, sent back in delivery requests. */
    val definitionId: String,
    val deliveryUrl: String,
    val host: String,
    val providerName: String,
    val itemName: String,
    val advertised: StoredAmount?,
    val paidSats: Long,
    val orderId: String,
    val orderToken: String?,
    val paymentHash: String,
    val preimage: String?,
    val paidAtMs: Long,
    /** The product's brand at purchase time, so a voucher keeps its look in Purchases. */
    val brand: xyz.lilsus.raylsuite.core.widgetspec.WidgetBrand? = null,
    val delivery: WidgetPurchaseDelivery = WidgetPurchaseDelivery()
)

data class WidgetPurchaseDelivery(
    val state: WidgetDeliveryState = WidgetDeliveryState.Pending,
    val message: String? = null,
    val items: List<WidgetDeliveryItem> = emptyList(),
    val presentation: xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryPresentation =
        xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryPresentation.Receipt,
    val checkedAtMs: Long? = null,
    /** The provider no longer recognizes the order; nothing more can be learned. */
    val lost: Boolean = false
)
