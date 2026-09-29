package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryItem
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryItemKind
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryPresentation
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryState
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInputType
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPurchase as WidgetOffer
import xyz.lilsus.raylsuite.core.widgetspec.WidgetStatusValue
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount

/** A provider widget face. Buttons carry action ids that the controller turns into payments. */
data class NativeProviderTile(
    val title: String,
    val providerName: String?,
    /** Base64 PNG for reviewed providers; otherwise the view draws [monogram]. */
    val icon: String?,
    /** `#RRGGBB` tile colour for reviewed providers; null draws the neutral tile. */
    val tint: String?,
    val monogram: String,
    /** What the tile calls itself next to the glyph: the provider's label for this size first. */
    val label: String,
    val host: String,
    val size: String,
    val purchaseType: String?,
    val headline: String,
    val price: String?,
    val inputHint: String?,
    val actions: List<NativeProviderAction>,
    val products: List<NativeProviderProduct>,
    val amountEntry: NativeProviderAmountEntry?,
    val status: NativeProviderStatus?,
    val attention: String?,
    val delivery: String?,
    val deliveryTone: String?
)

/** [kind] is `pay`, `enterAmount` or `more`; only `pay` goes straight to checkout. */
data class NativeProviderAction(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String?
)

data class NativeProviderStatus(
    val title: String?,
    val value: String?,
    val subtitle: String?,
    /** ISO 8601, set only when the value is overdue: fresh values do not show their age. */
    val observedAt: String?,
    val loading: Boolean,
    val stale: Boolean,
    /** Recent values, oldest first, for a sparkline; empty without a trend. */
    val trend: List<Double>,
    val trendPeriod: String?
)

data class NativeProviderAmountEntry(
    val title: String,
    val currencyCode: String,
    val fractionDigits: Int,
    val range: String,
    val presets: List<NativeProviderAction>,
    val payTitle: String,
    val cancelTitle: String
)

data class NativeProviderFamily(
    val id: String,
    val title: String,
    val detail: String,
    val preview: NativeProviderTile?
)

data class NativeProviderSetup(
    val phase: String,
    /** The family chooser, while `phase` is `families`. */
    val families: List<NativeProviderFamily>,
    val selectedFamily: String,
    val addTitle: String,
    val message: String?,
    val retryTitle: String,
    val title: String,
    val host: String,
    val icon: String?,
    val tint: String?,
    val monogram: String,
    val privacy: String?,
    val unreviewedWarning: String?,
    val callsItself: String?,
    val chooseTitle: String,
    val products: List<NativeProviderProduct>,
    val emptyProducts: String?,
    val inputs: List<NativeProviderInput>,
    val sizeTitle: String,
    val sizes: List<NativeProviderSize>,
    val selectedSize: String,
    val preview: NativeProviderTile?,
    val termsText: String?,
    val termsUrl: String?,
    val termsTitle: String,
    val saveTitle: String,
    val doneTitle: String,
    val busy: Boolean,
    val error: String?
)

data class NativeProviderProduct(
    val id: String,
    val name: String,
    val price: String,
    val detail: String?,
    val selected: Boolean,
    val available: Boolean
)

data class NativeProviderInput(
    val id: String,
    val label: String,
    val value: String,
    /** `phone`, `number`, `email` or `text` pick the keyboard; `choice` shows [options]. */
    val type: String,
    val secret: Boolean,
    val placeholder: String?,
    val prefix: String?,
    val invalid: Boolean,
    val message: String?,
    val options: List<NativeProviderOption>
)

data class NativeProviderOption(val value: String, val label: String)

data class NativeProviderSize(val id: String, val title: String)

private fun format(amount: StoredAmount): String = currentAmountFormatter().format(
    DisplayAmount(amount.minor, CurrencyCatalog.infoFor(amount.normalizedCurrencyCode).currency)
)

internal fun ProviderTile.toNative(): NativeProviderTile {
    val actions = actions.map { action ->
        when (action) {
            is ProviderTileAction.PayProduct -> NativeProviderAction(
                "$PRODUCT${action.productId}",
                "pay",
                if (size == ProviderWidgetSize.Small) {
                    hubText("provider_pay", format(action.price))
                } else {
                    action.name
                },
                format(action.price).takeIf { size != ProviderWidgetSize.Small }
            )

            is ProviderTileAction.PayAmount ->
                NativeProviderAction(amountId(action.amount), "pay", format(action.amount), null)

            ProviderTileAction.EnterAmount -> NativeProviderAction(
                ENTER_AMOUNT,
                "enterAmount",
                hubText(
                    if (size ==
                        ProviderWidgetSize.Small
                    ) {
                        "provider_top_up"
                    } else {
                        "provider_other_amount"
                    }
                ),
                null
            )

            ProviderTileAction.Lookup ->
                NativeProviderAction(LOOKUP, "pay", hubText("provider_check_bill"), null)

            ProviderTileAction.More ->
                NativeProviderAction(MORE, "more", hubText("provider_more"), null)
        }
    }
    val purchase = lastPurchase
    val (delivery, tone) = when {
        purchase == null -> null to null

        purchase.delivery.lost || purchase.delivery.state == WidgetDeliveryState.Failed ->
            hubText("provider_not_delivered") to "failure"

        !purchase.delivery.state.terminal -> hubText("provider_delivery_processing") to "pending"

        else -> null to null
    }
    return NativeProviderTile(
        title = title,
        providerName = providerName,
        icon = brand?.iconPng,
        tint = brand?.tint,
        monogram = (providerName ?: title).take(1).uppercase(),
        label = label ?: title,
        host = host,
        size = size.wireName,
        purchaseType = purchaseType,
        headline = headline,
        price = price?.let(::format),
        inputHint = inputHint,
        actions = actions,
        products = products.map {
            NativeProviderProduct(it.id, it.name, format(it.price), it.detail, false, it.available)
        },
        amountEntry = amountRange?.let { range ->
            NativeProviderAmountEntry(
                title = title,
                currencyCode = range.currencyCode,
                fractionDigits = CurrencyCatalog.infoFor(range.currencyCode).fractionDigits,
                range = "${format(range.min)} – ${format(range.max)}",
                presets = range.presets.map {
                    NativeProviderAction(amountId(it), "pay", format(it), null)
                },
                payTitle = hubText("provider_pay_amount"),
                cancelTitle = hubText("hub_canvas_remove_cancel")
            )
        },
        status = status?.let {
            NativeProviderStatus(
                title = it.value?.title,
                value = it.value?.value,
                subtitle = it.value?.subtitle,
                observedAt = it.value?.takeIf { value -> it.stale || value.overdue() }?.observedAt,
                loading = it.loading,
                stale = it.stale,
                trend = it.value?.trend?.points.orEmpty(),
                trendPeriod = it.value?.trend?.period
            )
        },
        attention = when {
            blocked -> hubText("provider_blocked")
            needsAttention -> hubText("provider_needs_attention")
            else -> null
        },
        delivery = delivery,
        deliveryTone = tone
    )
}

internal fun ProviderSetupState.toNative(catalog: ServiceCatalog): NativeProviderSetup {
    val base = NativeProviderSetup(
        phase = "loading",
        families = emptyList(),
        selectedFamily = "",
        addTitle = hubText("hub_widget_add"),
        message = hubText("provider_loading"),
        retryTitle = hubText("provider_try_again"),
        title = "",
        host = url,
        icon = null,
        tint = null,
        monogram = "",
        privacy = null,
        unreviewedWarning = null,
        callsItself = null,
        chooseTitle = hubText("provider_choose"),
        products = emptyList(),
        emptyProducts = null,
        inputs = emptyList(),
        sizeTitle = hubText("provider_size"),
        sizes = emptyList(),
        selectedSize = "",
        preview = null,
        termsText = null,
        termsUrl = null,
        termsTitle = hubText("provider_view_terms"),
        saveTitle = hubText("provider_agree_add"),
        doneTitle = hubText("hub_canvas_done"),
        busy = false,
        error = null
    )
    return when (val phase = phase) {
        ProviderSetupPhase.Loading -> base

        is ProviderSetupPhase.Families -> {
            val definition = phase.definition
            base.copy(
                phase = "families",
                message = null,
                title = definition.title,
                host = definition.host,
                icon = definition.reviewedBrand(catalog)?.iconPng,
                tint = definition.reviewedBrand(catalog)?.tint,
                monogram = definition.displayName.take(1).uppercase(),
                callsItself = hubText("provider_calls_itself", definition.displayName),
                families = definition.supportedFamilies.map { family ->
                    NativeProviderFamily(
                        id = family.wireName,
                        title = hubText("provider_size_${family.wireName}"),
                        detail = familyDetail(definition, family),
                        preview = previewTile(
                            catalog,
                            definition,
                            phase.body,
                            family,
                            definition.defaultProducts(family),
                            emptyMap()
                        )
                    )
                },
                selectedFamily = phase.selected.wireName
            )
        }

        is ProviderSetupPhase.Failed -> base.copy(
            phase = "failed",
            message = hubText(
                when (phase.failure) {
                    ProviderSetupFailure.NotResponding -> "provider_not_responding"
                    ProviderSetupFailure.Gone -> "provider_gone"
                    ProviderSetupFailure.Blocked -> "provider_blocked"
                    is ProviderSetupFailure.Invalid -> "provider_invalid"
                }
            )
        )

        is ProviderSetupPhase.Ready -> {
            val definition = phase.definition
            val inputError = phase.error as? ProviderSetupError.InvalidInput
            val preview = previewTile(
                catalog,
                definition,
                phase.body,
                phase.size,
                phase.productIds,
                definition.inputs.filterNot { it.secret }.associate { input ->
                    input.id to (input.normalize(phase.inputs[input.id].orEmpty()) ?: "")
                }
            )
            base.copy(
                phase = "ready",
                message = null,
                title = definition.title,
                host = definition.host,
                icon = definition.reviewedBrand(catalog)?.iconPng,
                tint = definition.reviewedBrand(catalog)?.tint,
                monogram = definition.displayName.take(1).uppercase(),
                privacy = definition.inputs.takeIf { it.isNotEmpty() }
                    ?.let { hubText("provider_privacy", definition.host) },
                // Only services in Rayl's catalog are reviewed; link-added widgets carry the warning.
                unreviewedWarning = hubText("provider_unreviewed").takeUnless {
                    definition.isReviewed(catalog)
                },
                callsItself = hubText("provider_calls_itself", definition.displayName),
                // Adding uses the family's defaults, changed later via Edit widget. The list only
                // shows when editing, or when no default is available to fall back on.
                products = if (existingWidgetId == null && phase.productIds.isNotEmpty()) {
                    emptyList()
                } else {
                    definition.products
                }.map {
                    NativeProviderProduct(
                        id = it.id,
                        name = it.name,
                        price = format(it.price),
                        detail = it.validity ?: it.description,
                        selected = it.id in phase.productIds,
                        available = it.available
                    )
                },
                emptyProducts = hubText("provider_no_products").takeIf {
                    definition.purchase is WidgetOffer.Product &&
                        definition.products.none { it.available }
                },
                inputs = definition.inputs.map { input ->
                    NativeProviderInput(
                        id = input.id,
                        label = input.label,
                        value = phase.inputs[input.id].orEmpty(),
                        type = input.type.wireName,
                        secret = input.secret,
                        placeholder = input.placeholder,
                        prefix = input.country?.takeIf { input.type == WidgetInputType.Phone }
                            ?.let(::callingCode)?.let { "+$it" },
                        invalid = input.id == inputError?.inputId,
                        message = inputError?.message?.takeIf { input.id == inputError.inputId },
                        options = input.options.map { NativeProviderOption(it.value, it.label) }
                    )
                },
                chooseTitle = hubText(
                    if (phase.size ==
                        ProviderWidgetSize.Small
                    ) {
                        "provider_choose_one"
                    } else {
                        "provider_choose_shown"
                    }
                ),
                sizes = emptyList(),
                selectedSize = phase.size.wireName,
                preview = preview,
                termsText = definition.terms?.let { hubText("provider_terms", definition.host) },
                termsUrl = definition.terms?.url?.value,
                saveTitle = hubText(
                    when {
                        existingWidgetId != null -> "hub_editor_save"
                        definition.terms != null -> "provider_agree_add"
                        else -> "hub_widget_add"
                    }
                ),
                busy = phase.validating,
                error = when (val error = phase.error) {
                    ProviderSetupError.ChooseProduct -> hubText("provider_choose_product")

                    is ProviderSetupError.InvalidInput -> error.message ?: hubText(
                        "provider_invalid_input",
                        definition.inputs.firstOrNull { it.id == error.inputId }?.label
                            ?: error.inputId
                    )

                    is ProviderSetupError.Rejected ->
                        error.message ?: hubText("provider_not_responding")

                    ProviderSetupError.SaveFailed -> hubText("provider_save_failed")

                    null -> null
                }
            )
        }
    }
}

/** Resolves a tile action id back into what the tap pays for. */
internal fun providerActionPayment(actionId: String): Pair<String?, StoredAmount?> = when {
    actionId.startsWith(PRODUCT) -> actionId.removePrefix(PRODUCT) to null

    actionId.startsWith(AMOUNT) -> {
        val parts = actionId.removePrefix(AMOUNT).split(':', limit = 2)
        val minor = parts.getOrNull(0)?.toLongOrNull()
        val currency = parts.getOrNull(1)
        null to if (minor != null && currency != null) StoredAmount(minor, currency) else null
    }

    else -> null to null
}

/** Parses an amount the user typed, e.g. "7.5", in [currencyCode]. */
internal fun typedAmount(text: String, currencyCode: String): StoredAmount? {
    val digits = CurrencyCatalog.infoFor(currencyCode).fractionDigits
    return text.replace(',', '.').parseMinorAmount(digits)?.takeIf { it > 0 }
        ?.let { StoredAmount(it, currencyCode) }
}

private fun amountId(amount: StoredAmount) =
    "$AMOUNT${amount.minor}:${amount.normalizedCurrencyCode}"

private const val PRODUCT = "product:"
private const val AMOUNT = "amount:"
private const val ENTER_AMOUNT = "enterAmount"
private const val LOOKUP = "lookup"
private const val MORE = "more"

private fun hubText(key: String): String = nativeString(NativeStringResource("PaymentHub", key))

private fun hubText(key: String, argument: String): String =
    nativeString(NativeStringResource("PaymentHub", key), argument)

data class NativeCatalogSection(
    val title: String,
    val entries: List<NativeCatalogEntry>,
    /** The section that ends with "Add by link". */
    val endsWithLink: Boolean
)

data class NativeCatalogEntry(
    val url: String,
    val title: String,
    val subtitle: String?,
    val preview: NativeProviderTile?,
    val loading: Boolean
)

data class NativePurchase(
    val id: String,
    val provider: String,
    val host: String,
    val item: String,
    val amount: String?,
    val paidAtMs: Long,
    val state: String,
    /** `success`, `pending` or `failure`. */
    val tone: String,
    val message: String?,
    val voucher: NativeVoucherCard?,
    val items: List<NativePurchaseItem>
)

/**
 * A voucher drawn natively as a card in the product's brand: its credentials, barcode and expiry.
 * Hosted-only vouchers (a `link`) have no card; their page is the voucher.
 */
data class NativeVoucherCard(
    val title: String,
    val value: String?,
    val icon: String?,
    val tint: String?,
    val monogram: String,
    val credentials: List<NativeVoucherCredential>,
    val barcode: String?,
    /** `qr`, `code128`, `pdf417` or `aztec`. */
    val barcodeFormat: String?,
    /** ISO date (YYYY-MM-DD); the view formats it. */
    val expiry: String?,
    val expiryLabel: String?
)

/** [secret] credentials, such as a PIN, stay hidden until the user reveals them. */
data class NativeVoucherCredential(val label: String, val value: String, val secret: Boolean)

/** The native card for a voucher delivery, or null when there is nothing native to draw. */
fun WidgetPurchase.voucherCard(): NativeVoucherCard? {
    if (delivery.presentation != WidgetDeliveryPresentation.Voucher) return null
    val credentials = delivery.items.filter {
        it.kind == WidgetDeliveryItemKind.Code ||
            it.kind == WidgetDeliveryItemKind.Pin
    }
    val barcode = delivery.items.firstOrNull { it.kind == WidgetDeliveryItemKind.Barcode }
    if (credentials.isEmpty() && barcode == null) return null
    val expiry = delivery.items.firstOrNull { it.kind == WidgetDeliveryItemKind.Date }
    return NativeVoucherCard(
        title = itemName,
        value = advertised?.let(::format),
        icon = brand?.iconPng,
        tint = brand?.tint,
        monogram = providerName.take(1).uppercase(),
        credentials = credentials.map {
            NativeVoucherCredential(
                it.label,
                it.value,
                secret =
                    it.kind == WidgetDeliveryItemKind.Pin
            )
        },
        barcode = barcode?.value,
        barcodeFormat = barcode?.format?.wireName,
        expiry = expiry?.value,
        expiryLabel = expiry?.label
    )
}

/** Items the voucher card already shows; the rest are listed under it. */
fun WidgetPurchase.shownOnCard(item: WidgetDeliveryItem): Boolean {
    if (voucherCard() == null) return false
    return item.kind == WidgetDeliveryItemKind.Code || item.kind == WidgetDeliveryItemKind.Pin ||
        item.kind == WidgetDeliveryItemKind.Barcode ||
        item == delivery.items.firstOrNull { it.kind == WidgetDeliveryItemKind.Date }
}

/** [page] items are HTTPS pages, such as a voucher, shown inside the app. */
data class NativePurchaseItem(
    val label: String,
    val value: String,
    val copyable: Boolean,
    val page: Boolean = false
)

private fun familyDetail(definition: WidgetDefinition, family: ProviderWidgetSize): String =
    hubText(
        when (definition.purchase) {
            is WidgetOffer.Product -> when (family) {
                ProviderWidgetSize.Small -> "family_small_product"
                ProviderWidgetSize.Medium -> "family_medium_product"
                ProviderWidgetSize.Large -> "family_large_product"
            }

            is WidgetOffer.Amount ->
                if (family ==
                    ProviderWidgetSize.Small
                ) {
                    "family_small_amount"
                } else {
                    "family_larger_amount"
                }

            else -> "family_status"
        }
    )

private fun previewTile(
    catalog: ServiceCatalog,
    definition: WidgetDefinition,
    body: String,
    family: ProviderWidgetSize,
    productIds: List<String>,
    inputs: Map<String, String>
): NativeProviderTile? = HubWidget(
    id = "preview:${family.wireName}",
    definitionId = definition.source.value,
    kind = HubWidgetKind.Provider,
    variant = family.variant(),
    provider = ProviderWidgetSetup(
        definitionUrl = definition.source.value,
        definitionBody = body,
        productIds = productIds,
        pinnedPrices = productIds.mapNotNull(definition::product).associate { it.id to it.price },
        inputs = inputs
    )
).providerTile(emptyList(), definition.snapshotStatus(), catalog)?.toNative()

internal fun catalogSections(
    items: List<ProviderCatalogItem>,
    catalog: ServiceCatalog
): List<NativeCatalogSection> = ProviderCatalogSection.entries.mapNotNull { section ->
    val entries = items.filter { it.entry.section == section }.map { item ->
        val definition = item.setup?.definition
        NativeCatalogEntry(
            url = item.entry.url,
            title = definition?.title ?: item.entry.url.substringAfterLast('/'),
            subtitle = definition?.let { it.providerName ?: it.description },
            preview = item.setup?.let { setup ->
                HubWidget(
                    id = "catalog:${item.entry.url}",
                    definitionId = item.entry.url,
                    kind = HubWidgetKind.Provider,
                    variant = (
                        setup.definition?.supportedFamilies?.first()
                            ?: ProviderWidgetSize.Small
                        )
                        .variant(),
                    provider = setup
                ).providerTile(
                    emptyList(),
                    setup.definition?.snapshotStatus(),
                    catalog
                )?.toNative()
            },
            loading = item.loading
        )
    }
    entries.takeIf { it.isNotEmpty() }?.let {
        NativeCatalogSection(
            hubText(
                when (section) {
                    ProviderCatalogSection.Mobile -> "hub_section_mobile"
                    ProviderCatalogSection.Bills -> "hub_section_bills"
                    ProviderCatalogSection.GiftCards -> "hub_section_gift_cards"
                    ProviderCatalogSection.OtherServices -> "hub_section_other_services"
                    ProviderCatalogSection.Info -> "hub_section_info"
                }
            ),
            it,
            endsWithLink = section == ProviderCatalogSection.OtherServices
        )
    }
}

internal fun WidgetPurchase.toNativePurchase(): NativePurchase {
    val (state, tone) = when {
        delivery.lost || delivery.state == WidgetDeliveryState.Failed ->
            hubText("provider_not_delivered") to "failure"

        delivery.state == WidgetDeliveryState.Delivered -> hubText("purchase_delivered") to
            "success"

        delivery.state == WidgetDeliveryState.Expired -> hubText("purchase_expired") to "failure"

        else -> hubText("purchase_processing") to "pending"
    }
    return NativePurchase(
        id = id,
        provider = providerName,
        host = host,
        item = itemName,
        amount = advertised?.let(::format),
        paidAtMs = paidAtMs,
        state = state,
        tone = tone,
        message = delivery.message,
        voucher = voucherCard(),
        items = delivery.items.filterNot(::shownOnCard).map {
            NativePurchaseItem(
                it.label,
                it.value,
                it.kind in COPYABLE,
                page = it.opensInApp
            )
        }.sortedByDescending { it.page } +
            NativePurchaseItem(hubText("purchase_order"), orderId, copyable = true)
    )
}

internal fun providerSizeChoices(families: List<ProviderWidgetSize>): List<NativeHubSizeChoice> =
    families.map { NativeHubSizeChoice(it.wireName, hubText("provider_size_${it.wireName}")) }

/** A reading is overdue once it missed two refreshes; samples (no time) never are. */
private fun WidgetStatusValue.overdue(): Boolean {
    val observed = runCatching { Instant.parse(observedAt) }.getOrNull() ?: return false
    val allowed = (refreshAfterSeconds * 2).coerceAtLeast(120).seconds
    return Clock.System.now() - observed > allowed
}

/** Credentials a user may need to paste elsewhere. */
val COPYABLE =
    setOf(WidgetDeliveryItemKind.Code, WidgetDeliveryItemKind.Pin, WidgetDeliveryItemKind.Barcode)
