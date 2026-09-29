package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.widgetspec.WidgetBrand
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryItem
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryItemKind
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryState

/** One app-scoped document keeps contacts, widget instances and action history consistent. */
class DefaultPaymentHubRepository(
    private val settings: Settings,
    private val idGenerator: () -> String = { Random.nextLong().toULong().toString(16) }
) : PaymentHubRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableHub = MutableStateFlow(load())
    override val hub = mutableHub.asStateFlow()

    override suspend fun saveContact(address: LightningAddress, title: String?): HubContact {
        lateinit var result: HubContact
        mutate { current ->
            val existing = current.contacts.firstOrNull { it.address.isSameAddressAs(address) }
            result = HubContact(
                existing?.id ?: "contact:${idGenerator()}",
                title?.trim()?.takeIf(String::isNotEmpty) ?: existing?.title ?: address.username,
                address
            )
            current.withContact(result)
        }
        return result
    }

    override suspend fun deleteContact(id: String) {
        mutate { it.copy(contacts = it.contacts.filterNot { contact -> contact.id == id }) }
    }

    override suspend fun saveWidget(draft: HubWidgetDraft, id: String?): HubWidget? {
        var result: HubWidget? = null
        mutate { current ->
            if (id != null && current.widget(id) == null) return@mutate current
            val contacts = draft.contactIds.distinct()
            val local = LocalHubWidgets.definitions.firstOrNull { it.id == draft.definitionId }
            if (local == null || local.kind != draft.kind || draft.variant !in local.variants) {
                return@mutate current
            }
            if (draft.kind == HubWidgetKind.Contacts || draft.kind == HubWidgetKind.Shortcut) {
                if (contacts.isEmpty() || contacts.size > draft.variant.capacity ||
                    contacts.any { current.contact(it) == null }
                ) {
                    return@mutate current
                }
            }
            var updated = current
            var targetId: HubItemId? = null
            when (draft.kind) {
                HubWidgetKind.Contacts -> contacts.forEach {
                    updated = updated.withContactAction(it)
                }

                HubWidgetKind.Shortcut -> {
                    val amount = draft.amount ?: return@mutate current
                    if (amount.minor <= 0 ||
                        amount.normalizedCurrencyCode !in CurrencyCatalog.supportedCodes ||
                        contacts.size != 1 || draft.variant != LocalHubWidgets.Single
                    ) {
                        return@mutate current
                    }
                    val normalizedAmount = StoredAmount(amount.minor, amount.normalizedCurrencyCode)
                    val comment = draft.comment?.trim()?.takeIf(String::isNotEmpty)
                    val existing = current.targets.firstOrNull {
                        it.contactId == contacts.single() &&
                            it.amountRule == DirectTargetAmountRule.Preset(normalizedAmount) &&
                            it.comment == comment
                    }
                    val target = existing ?: DirectPaymentTarget(
                        id = HubItemId("preset:${idGenerator()}"),
                        title = current.contact(contacts.single())!!.title,
                        contactId = contacts.single(),
                        amountRule = DirectTargetAmountRule.Preset(normalizedAmount),
                        comment = comment
                    )
                    targetId = target.id
                    if (existing == null) updated = updated.copy(targets = updated.targets + target)
                }

                else -> Unit
            }
            val widget = HubWidget(
                id = id ?: "widget:${idGenerator()}",
                definitionId = draft.definitionId,
                kind = draft.kind,
                variant = draft.variant,
                contactIds = if (draft.kind == HubWidgetKind.Contacts) contacts else emptyList(),
                targetId = targetId
            )
            result = widget
            updated.copy(
                widgets = if (id == null) {
                    updated.widgets + widget
                } else {
                    updated.widgets.map { if (it.id == id) widget else it }
                }
            )
        }
        return result
    }

    override suspend fun deleteWidget(id: String) {
        // Removing presentation never removes a contact or its successful payment history.
        mutate { it.copy(widgets = it.widgets.filterNot { widget -> widget.id == id }) }
    }

    override suspend fun moveWidget(id: String, index: Int) {
        mutate { current ->
            val from = current.widgets.indexOfFirst { it.id == id }
            if (from < 0) return@mutate current
            val ordered = current.widgets.toMutableList()
            val item = ordered.removeAt(from)
            ordered.add(index.coerceIn(0, ordered.size), item)
            current.copy(widgets = ordered)
        }
    }

    override suspend fun saveContactAndWidget(
        address: LightningAddress,
        title: String
    ): HubContact {
        lateinit var saved: HubContact
        mutate { current ->
            saved = current.contacts.firstOrNull { it.address.isSameAddressAs(address) }
                ?: HubContact(
                    "contact:${idGenerator()}",
                    title.trim().ifEmpty {
                        address.username
                    },
                    address
                )
            val updated = current.withContact(saved).withContactAction(saved.id)
            if (updated.widgets.any { saved.id in it.contactIds }) return@mutate updated
            updated.copy(
                widgets = updated.widgets + HubWidget(
                    id = "widget:${idGenerator()}",
                    definitionId = "local.contacts",
                    kind = HubWidgetKind.Contacts,
                    variant = LocalHubWidgets.Single,
                    contactIds = listOf(saved.id)
                )
            )
        }
        return saved
    }

    override suspend fun recordSuccessfulPayment(id: HubItemId, paidAtMs: Long, paidSats: Long?) {
        mutate { current ->
            current.copy(
                targets = current.targets.map { target ->
                    if (target.id != id) {
                        target
                    } else {
                        target.copy(
                            stats = HubItemStats(
                                successfulPaymentCount =
                                    (target.stats.successfulPaymentCount + 1)
                                        .coerceAtLeast(target.stats.successfulPaymentCount),
                                lastSuccessfulPaymentAtMs = paidAtMs,
                                lastPaidSats =
                                    paidSats?.takeIf { it > 0 } ?: target.stats.lastPaidSats
                            )
                        )
                    }
                }
            )
        }
    }

    override suspend fun saveProviderWidget(
        setup: ProviderWidgetSetup,
        variant: HubWidgetVariant,
        id: String?
    ): HubWidget? {
        var result: HubWidget? = null
        mutate { current ->
            if (id != null && current.widget(id) == null) return@mutate current
            if (setup.definition == null) return@mutate current
            val widget = HubWidget(
                id = id ?: "widget:${idGenerator()}",
                definitionId = setup.definitionUrl,
                kind = HubWidgetKind.Provider,
                variant = variant,
                provider = setup
            )
            result = widget
            current.copy(
                widgets = if (id == null) {
                    current.widgets + widget
                } else {
                    current.widgets.map { if (it.id == id) widget else it }
                }
            )
        }
        return result
    }

    override suspend fun recordPurchase(purchase: WidgetPurchase) {
        mutate { current ->
            if (current.purchase(purchase.id) != null) return@mutate current
            current.copy(purchases = listOf(purchase) + current.purchases)
        }
    }

    override suspend fun updatePurchase(id: String, transform: (WidgetPurchase) -> WidgetPurchase) {
        mutate { current ->
            current.copy(
                purchases = current.purchases.map {
                    if (it.id ==
                        id
                    ) {
                        transform(it)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun PaymentHub.withContact(contact: HubContact): PaymentHub = copy(
        contacts = if (contacts.any { it.id == contact.id }) {
            contacts.map { if (it.id == contact.id) contact else it }
        } else {
            contacts + contact
        }
    )

    private fun PaymentHub.withContactAction(id: String): PaymentHub {
        if (contactTarget(id) != null) return this
        val contact = contact(id) ?: return this
        return copy(
            targets = targets + DirectPaymentTarget(
                id = HubItemId("contact-payment:$id"),
                title = contact.title,
                contactId = id,
                amountRule = DirectTargetAmountRule.AskEveryTime
            )
        )
    }

    private suspend fun mutate(transform: (PaymentHub) -> PaymentHub) {
        mutex.withLock {
            val current = mutableHub.value
            val next = transform(current).normalized()
            if (next == current) return
            settings.putString(DOCUMENT_KEY, json.encodeToString(next.toRecord()))
            mutableHub.value = next
        }
    }

    private fun load(): PaymentHub = settings.getStringOrNull(DOCUMENT_KEY)?.let { encoded ->
        runCatching {
            val record = json.decodeFromString<HubRecord>(encoded)
            require(record.schemaVersion == SCHEMA_VERSION)
            record.toHub().normalized()
        }.getOrNull()
    } ?: PaymentHub()

    private companion object {
        const val DOCUMENT_KEY = "paymentHub.widgets.document"
        const val SCHEMA_VERSION = 1
    }
}

internal expect fun platformCurrentTimeMillis(): Long

@Serializable
private data class HubRecord(
    val schemaVersion: Int = 1,
    val contacts: List<ContactRecord>,
    val actions: List<ActionRecord>,
    val widgets: List<WidgetRecord>,
    val purchases: List<PurchaseRecord> = emptyList()
)

@Serializable
private data class ContactRecord(val id: String, val title: String, val address: String)

@Serializable
private data class ActionRecord(
    val id: String,
    val title: String,
    val contactId: String,
    val minor: Long? = null,
    val currency: String? = null,
    val comment: String? = null,
    val count: Long = 0,
    val lastPaid: Long? = null,
    val lastSats: Long? = null
)

@Serializable
private data class WidgetRecord(
    val id: String,
    val definitionId: String,
    val kind: String,
    val variantId: String,
    val columns: Int,
    val rows: Int,
    val capacity: Int,
    val contactIds: List<String> = emptyList(),
    val actionId: String? = null,
    val provider: ProviderRecord? = null
)

@Serializable
private data class AmountRecord(val minor: Long, val currency: String)

@Serializable
private data class ProviderRecord(
    val definitionUrl: String,
    val definitionBody: String,
    val etag: String? = null,
    val productIds: List<String> = emptyList(),
    val pinnedPrices: Map<String, AmountRecord> = emptyMap(),
    val inputs: Map<String, String> = emptyMap(),
    val acceptedTermsVersion: String? = null,
    val accountHint: String? = null
)

@Serializable
private data class DeliveryItemRecord(
    val kind: String,
    val label: String,
    val value: String,
    val format: String? = null
)

@Serializable
private data class PurchaseRecord(
    val id: String,
    val widgetId: String? = null,
    val definitionUrl: String,
    val definitionId: String,
    val deliveryUrl: String,
    val host: String,
    val providerName: String,
    val itemName: String,
    val advertised: AmountRecord? = null,
    val paidSats: Long,
    val orderId: String,
    val orderToken: String? = null,
    val paymentHash: String,
    val preimage: String? = null,
    val paidAtMs: Long,
    val deliveryState: String,
    val deliveryMessage: String? = null,
    val deliveryItems: List<DeliveryItemRecord> = emptyList(),
    val deliveryPresentation: String? = null,
    val brandName: String? = null,
    val brandIcon: String? = null,
    val brandTint: String? = null,
    val checkedAtMs: Long? = null,
    val lost: Boolean = false
)

private fun StoredAmount.toRecord() = AmountRecord(minor, normalizedCurrencyCode)

private fun AmountRecord.toAmount() = StoredAmount(minor, currency)

private fun ProviderWidgetSetup.toRecord() = ProviderRecord(
    definitionUrl,
    definitionBody,
    etag,
    productIds,
    pinnedPrices.mapValues { it.value.toRecord() },
    inputs,
    acceptedTermsVersion,
    accountHint
)

private fun ProviderRecord.toSetup() = ProviderWidgetSetup(
    definitionUrl,
    definitionBody,
    etag,
    productIds,
    pinnedPrices.mapValues { it.value.toAmount() },
    inputs,
    acceptedTermsVersion,
    accountHint
)

private fun WidgetPurchase.toRecord() = PurchaseRecord(
    id = id,
    widgetId = widgetId,
    definitionUrl = definitionUrl,
    definitionId = definitionId,
    deliveryUrl = deliveryUrl,
    host = host,
    providerName = providerName,
    itemName = itemName,
    advertised = advertised?.toRecord(),
    paidSats = paidSats,
    orderId = orderId,
    orderToken = orderToken,
    paymentHash = paymentHash,
    preimage = preimage,
    paidAtMs = paidAtMs,
    deliveryState = delivery.state.wireName,
    deliveryMessage = delivery.message,
    deliveryItems = delivery.items.map {
        DeliveryItemRecord(it.kind.wireName, it.label, it.value, it.format?.wireName)
    },
    deliveryPresentation = delivery.presentation.wireName,
    brandName = brand?.name,
    brandIcon = brand?.iconPng,
    brandTint = brand?.tint,
    checkedAtMs = delivery.checkedAtMs,
    lost = delivery.lost
)

private fun PurchaseRecord.toPurchase() = WidgetPurchase(
    id = id,
    widgetId = widgetId,
    definitionUrl = definitionUrl,
    definitionId = definitionId,
    deliveryUrl = deliveryUrl,
    host = host,
    providerName = providerName,
    itemName = itemName,
    advertised = advertised?.toAmount(),
    paidSats = paidSats,
    orderId = orderId,
    orderToken = orderToken,
    paymentHash = paymentHash,
    preimage = preimage,
    paidAtMs = paidAtMs,
    brand = if (brandIcon == null && brandTint == null &&
        brandName == null
    ) {
        null
    } else {
        WidgetBrand(brandName, brandIcon, brandTint)
    },
    delivery = WidgetPurchaseDelivery(
        state = WidgetDeliveryState.entries.firstOrNull { it.wireName == deliveryState }
            ?: WidgetDeliveryState.Pending,
        message = deliveryMessage,
        items = deliveryItems.map { item ->
            WidgetDeliveryItem(
                WidgetDeliveryItemKind.entries.firstOrNull { it.wireName == item.kind }
                    ?: WidgetDeliveryItemKind.Text,
                item.label,
                item.value,
                xyz.lilsus.raylsuite.core.widgetspec.WidgetBarcodeFormat.entries
                    .firstOrNull { it.wireName == item.format }
            )
        },
        presentation = xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryPresentation.entries
            .firstOrNull { it.wireName == deliveryPresentation }
            ?: xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryPresentation.Receipt,
        checkedAtMs = checkedAtMs,
        lost = lost
    )
)

private fun PaymentHub.toRecord() = HubRecord(
    contacts = contacts.map { ContactRecord(it.id, it.title, it.address.full) },
    actions = targets.map { target ->
        val amount = (target.amountRule as? DirectTargetAmountRule.Preset)?.amount
        ActionRecord(
            target.id.value,
            target.title,
            target.contactId,
            amount?.minor,
            amount?.normalizedCurrencyCode,
            target.comment,
            target.stats.successfulPaymentCount,
            target.stats.lastSuccessfulPaymentAtMs,
            target.stats.lastPaidSats
        )
    },
    widgets = widgets.map { widget ->
        WidgetRecord(
            widget.id, widget.definitionId, widget.kind.name,
            widget.variant.id, widget.variant.columns, widget.variant.rows, widget.variant.capacity,
            widget.contactIds, widget.targetId?.value, widget.provider?.toRecord()
        )
    },
    purchases = purchases.map { it.toRecord() }
)

private fun HubRecord.toHub() = PaymentHub(
    contacts = contacts.mapNotNull { record ->
        LightningAddress.parse(record.address)?.let { HubContact(record.id, record.title, it) }
    },
    targets = actions.mapNotNull { record ->
        val amount = if (record.minor != null && record.currency != null) {
            if (record.minor <= 0 ||
                record.currency !in CurrencyCatalog.supportedCodes
            ) {
                return@mapNotNull null
            }
            DirectTargetAmountRule.Preset(StoredAmount(record.minor, record.currency))
        } else if (record.minor == null && record.currency == null) {
            DirectTargetAmountRule.AskEveryTime
        } else {
            return@mapNotNull null
        }
        DirectPaymentTarget(
            HubItemId(record.id),
            record.title,
            record.contactId,
            amount,
            record.comment,
            HubItemStats(record.count.coerceAtLeast(0), record.lastPaid, record.lastSats)
        )
    },
    widgets = widgets.mapNotNull { record ->
        val kind =
            HubWidgetKind.entries.firstOrNull { it.name == record.kind } ?: return@mapNotNull null
        if (record.columns !in 1..2 || record.rows !in 1..2) return@mapNotNull null
        HubWidget(
            record.id,
            record.definitionId,
            kind,
            HubWidgetVariant(record.variantId, record.columns, record.rows, record.capacity),
            record.contactIds,
            record.actionId?.let(::HubItemId),
            record.provider?.toSetup()
        )
    },
    purchases = purchases.map { it.toPurchase() }
)
