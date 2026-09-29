package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.jvm.JvmInline
import xyz.lilsus.raylsuite.core.model.StoredAmount

/** Identifies a payment action independently of the widgets that present it. */
@JvmInline
value class HubItemId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** A saved contact payment action. Multiple widgets may present the same action. */
data class DirectPaymentTarget(
    val id: HubItemId,
    val title: String,
    val contactId: String,
    val amountRule: DirectTargetAmountRule,
    val comment: String? = null,
    val stats: HubItemStats = HubItemStats()
)

sealed interface DirectTargetAmountRule {
    data object AskEveryTime : DirectTargetAmountRule
    data class Preset(val amount: StoredAmount) : DirectTargetAmountRule
}

data class HubItemStats(
    val successfulPaymentCount: Long = 0,
    val lastSuccessfulPaymentAtMs: Long? = null,
    /** What the last successful payment sent, for context such as a large contacts tile. */
    val lastPaidSats: Long? = null
)

/** A placed instance, independent of its catalog definition and of payment action identity. */
data class HubWidget(
    val id: String,
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val contactIds: List<String> = emptyList(),
    val targetId: HubItemId? = null,
    val provider: ProviderWidgetSetup? = null
)

/** Ordered widgets, the local contact book, and successful payment history share one atomic write. */
data class PaymentHub(
    val contacts: List<HubContact> = emptyList(),
    val targets: List<DirectPaymentTarget> = emptyList(),
    val widgets: List<HubWidget> = emptyList(),
    val purchases: List<WidgetPurchase> = emptyList()
) {
    fun contact(id: String): HubContact? = contacts.firstOrNull { it.id == id }
    fun target(id: HubItemId): DirectPaymentTarget? = targets.firstOrNull { it.id == id }
    fun widget(id: String): HubWidget? = widgets.firstOrNull { it.id == id }
    fun purchase(id: String): WidgetPurchase? = purchases.firstOrNull { it.id == id }
    fun contactTarget(id: String): DirectPaymentTarget? = targets.firstOrNull {
        it.contactId == id && it.amountRule == DirectTargetAmountRule.AskEveryTime
    }

    /**
     * The contacts a new or growing contacts widget starts with: most paid first, then most
     * recently paid, then in contact book order.
     */
    fun rankedContactIds(): List<String> {
        val byContact = targets.groupBy { it.contactId }
        return contacts.withIndex().sortedWith(
            compareByDescending<IndexedValue<HubContact>> { (_, contact) ->
                byContact[contact.id].orEmpty().sumOf { it.stats.successfulPaymentCount }
            }.thenByDescending { (_, contact) ->
                byContact[contact.id].orEmpty().maxOfOrNull {
                    it.stats.lastSuccessfulPaymentAtMs
                        ?: 0L
                }
                    ?: 0L
            }.thenBy { it.index }
        ).map { it.value.id }
    }

    fun normalized(): PaymentHub {
        val uniqueContacts = contacts.distinctBy { it.id }
        val contactIds = uniqueContacts.mapTo(mutableSetOf()) { it.id }
        val uniqueTargets = targets.filter { it.contactId in contactIds }.distinctBy { it.id }
        val targetIds = uniqueTargets.mapTo(mutableSetOf()) { it.id }
        val normalizedWidgets = widgets.distinctBy { it.id }.mapNotNull { widget ->
            when (widget.kind) {
                HubWidgetKind.Contacts -> widget.copy(
                    contactIds = widget.contactIds.filter { it in contactIds }.distinct()
                ).takeIf { it.contactIds.isNotEmpty() }

                HubWidgetKind.Shortcut -> widget.takeIf { it.targetId in targetIds }

                HubWidgetKind.Provider -> widget.takeIf { it.provider != null }

                else -> widget
            }
        }
        return PaymentHub(
            uniqueContacts,
            uniqueTargets,
            normalizedWidgets,
            purchases.distinctBy { it.id }.sortedByDescending { it.paidAtMs }
        )
    }
}
