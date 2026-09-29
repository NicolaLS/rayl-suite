package xyz.lilsus.raylsuite.feature.paymenthub.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule
import xyz.lilsus.raylsuite.feature.paymenthub.HubItemId
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.ProviderSecrets
import xyz.lilsus.raylsuite.feature.paymenthub.ProviderWidgetSetup
import xyz.lilsus.raylsuite.feature.paymenthub.RaylServices
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetPurchase
import xyz.lilsus.raylsuite.feature.paymenthub.platformCurrentTimeMillis
import xyz.lilsus.raylsuite.feature.paymenthub.reviewedBrand
import xyz.lilsus.raylsuite.integration.widget.WidgetClient

/**
 * Shared hub host logic: owns the post-payment save prompt and emits [DirectTargetPaymentIntent]
 * when a target is chosen. Each app maps that intent into its own provider-native payment flow.
 * Widget placement belongs to the Hub presentation, not here.
 */
class PaymentHubController(
    private val repository: PaymentHubRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
    widgetClient: WidgetClient = WidgetClient(),
    /** Rayl's catalog and block list; checkout checks it again right before paying. */
    val services: RaylServices = RaylServices.offline(scope)
) {
    private val mutableState = MutableStateFlow(PaymentHubHostState())
    val state: StateFlow<PaymentHubHostState> = mutableState.asStateFlow()

    private val mutablePaymentRequests =
        MutableSharedFlow<DirectTargetPaymentIntent>(extraBufferCapacity = 4)
    val paymentRequests: SharedFlow<DirectTargetPaymentIntent> =
        mutablePaymentRequests.asSharedFlow()

    private val mutableWidgetRequests =
        MutableSharedFlow<WidgetCheckoutIntent>(extraBufferCapacity = 4)

    /** Provider widget taps. Providers prepare, confirm and pay them like any invoice. */
    val widgetPaymentRequests: SharedFlow<WidgetCheckoutIntent> =
        mutableWidgetRequests.asSharedFlow()

    private val mutableWidgetLinks = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Widget links scanned or pasted on Scan; the Hub opens their setup. */
    val widgetLinks: SharedFlow<String> = mutableWidgetLinks.asSharedFlow()

    private val hub get() = repository.hub.value
    private var saving = false
    private val delivery =
        WidgetDeliveryTracker(repository, widgetClient, scope, clock, ::refreshDelivery)
    private var shownPurchaseId: String? = null

    init {
        delivery.resumeUnfinished()
        services.refresh()
    }

    fun dispatch(intent: PaymentHubIntent) {
        when (intent) {
            is PaymentHubIntent.SelectItem -> selectItem(intent.id)

            PaymentHubIntent.OpenScanner -> mutableState.update { it.copy(scannerRequested = true) }

            PaymentHubIntent.DismissScanner ->
                mutableState.update { it.copy(scannerRequested = false) }

            is PaymentHubIntent.SavePromptTitleChanged ->
                if (!saving) {
                    mutableState.update { current ->
                        current.copy(savePrompt = current.savePrompt?.copy(title = intent.title))
                    }
                }

            PaymentHubIntent.SavePromptSave -> savePrompt()

            PaymentHubIntent.SavePromptDismiss -> mutableState.update { it.copy(savePrompt = null) }

            is PaymentHubIntent.PayWidget -> payWidget(intent)

            PaymentHubIntent.DeliveryDismiss -> showDelivery(null)
        }
    }

    /** Reports a terminal successful wallet payment that was started from [targetId]. */
    fun recordSuccessfulPayment(targetId: HubItemId, paidSats: Long? = null) {
        val paidAtMs = clock()
        scope.launch { repository.recordSuccessfulPayment(targetId, paidAtMs, paidSats) }
    }

    /** Stores a paid provider order, shows its delivery and follows it until it settles. */
    fun recordWidgetPurchase(order: WidgetPaidOrder) {
        val definition = order.definition
        val purchase = WidgetPurchase(
            id = "purchase:${order.paymentHash}",
            widgetId = order.intent.widgetId,
            definitionUrl = order.intent.setup.definitionUrl,
            definitionId = definition.id,
            deliveryUrl = definition.deliveryUrl?.value ?: return,
            host = definition.host,
            providerName = definition.displayName,
            itemName = order.itemName,
            advertised = order.advertised,
            paidSats = order.paidMsats / 1_000,
            orderId = order.orderId,
            orderToken = order.orderToken,
            paymentHash = order.paymentHash,
            preimage = order.preimage,
            paidAtMs = clock(),
            brand = definition.reviewedBrand(services.catalog.value)
        )
        services.report(purchase.definitionUrl, UsageEventKind.Paid)
        scope.launch {
            repository.recordPurchase(purchase)
            showDelivery(purchase.id)
            delivery.follow(purchase.id)
        }
    }

    /** Hands a scanned or pasted widget link to the Hub. */
    fun openWidgetLink(url: String) {
        mutableState.update { it.copy(scannerRequested = false) }
        mutableWidgetLinks.tryEmit(url)
    }

    /** Shows a stored purchase's delivery again, for example from a receipt. */
    fun showPurchase(id: String) {
        if (hub.purchase(id) == null) return
        showDelivery(id)
        delivery.follow(id)
    }

    private fun showDelivery(id: String?) {
        shownPurchaseId = id
        refreshDelivery()
    }

    private fun refreshDelivery() {
        val purchase = shownPurchaseId?.let(hub::purchase)
        mutableState.update { it.copy(delivery = purchase) }
    }

    /** Offers to save a new contact together with a single Contacts widget. */
    fun offerSave(address: LightningAddress) {
        if (hub.contacts.any { it.address.isSameAddressAs(address) }) return
        mutableState.update { current ->
            current.copy(
                savePrompt = HubSavePrompt(address = address, title = address.username)
            )
        }
    }

    fun resetSession() {
        shownPurchaseId = null
        mutableState.update {
            it.copy(savePrompt = null, scannerRequested = false, delivery = null)
        }
    }

    private fun payWidget(intent: PaymentHubIntent.PayWidget) {
        val widget = hub.widget(intent.widgetId)
            ?.takeIf { it.kind == HubWidgetKind.Provider } ?: return
        val setup = widget.provider ?: return
        if (services.blocks(setup.definitionUrl)) return
        val productId = intent.productId ?: setup.productIds.firstOrNull()
        mutableState.update { it.copy(scannerRequested = false) }
        mutableWidgetRequests.tryEmit(
            WidgetCheckoutIntent(
                widgetId = widget.id,
                setup = setup,
                productId = productId,
                amount = intent.amount,
                inputs = ProviderSecrets.inputsFor(setup)
            )
        )
    }

    private fun selectItem(id: HubItemId) {
        val snapshot = hub
        val target = snapshot.target(id) ?: return
        val contact = snapshot.contact(target.contactId) ?: return
        mutableState.update { it.copy(scannerRequested = false) }
        mutablePaymentRequests.tryEmit(
            DirectTargetPaymentIntent(
                targetId = target.id,
                address = contact.address,
                amountRule = target.amountRule,
                comment = target.comment
            )
        )
    }

    private fun savePrompt() {
        if (saving) return
        val prompt = mutableState.value.savePrompt ?: return
        saving = true
        scope.launch {
            try {
                repository.saveContactAndWidget(
                    prompt.address,
                    prompt.title.trim().ifEmpty { prompt.address.username }
                )
                mutableState.update {
                    if (it.savePrompt ==
                        prompt
                    ) {
                        it.copy(savePrompt = null)
                    } else {
                        it
                    }
                }
            } finally {
                saving = false
            }
        }
    }
}

data class PaymentHubHostState(
    val savePrompt: HubSavePrompt? = null,
    /** A provider order whose delivery the Scan tab shows after paying. */
    val delivery: WidgetPurchase? = null,
    /** A lens asked for the host scanner surface to be shown prominently. */
    val scannerRequested: Boolean = false
) {
    val hasModalContent: Boolean
        get() = savePrompt != null
}

data class HubSavePrompt(val address: LightningAddress, val title: String)

sealed interface PaymentHubIntent {
    data class SelectItem(val id: HubItemId) : PaymentHubIntent

    data object OpenScanner : PaymentHubIntent

    data object DismissScanner : PaymentHubIntent

    data class SavePromptTitleChanged(val title: String) : PaymentHubIntent

    data object SavePromptSave : PaymentHubIntent

    data object SavePromptDismiss : PaymentHubIntent

    /** [productId] overrides the pinned product; [amount] is for `amount` widgets. */
    data class PayWidget(
        val widgetId: String,
        val productId: String? = null,
        val amount: StoredAmount? = null
    ) : PaymentHubIntent

    data object DeliveryDismiss : PaymentHubIntent
}

/** A provider widget tap, with everything the checkout needs and nothing it has to look up. */
data class WidgetCheckoutIntent(
    val widgetId: String?,
    val setup: ProviderWidgetSetup,
    val productId: String?,
    val amount: StoredAmount?,
    /** Every input the provider needs, secrets included. Never log or persist this. */
    val inputs: Map<String, String>
)

/** What a provider reports back after paying a widget invoice. */
data class WidgetPaidOrder(
    val intent: WidgetCheckoutIntent,
    val definition: WidgetDefinition,
    val itemName: String,
    val advertised: StoredAmount?,
    val orderId: String,
    val orderToken: String?,
    val paymentHash: String,
    val preimage: String?,
    val paidMsats: Long
)

/** What the hub hands to an app when a direct target is chosen. */
data class DirectTargetPaymentIntent(
    val targetId: HubItemId,
    val address: LightningAddress,
    val amountRule: DirectTargetAmountRule,
    val comment: String?
)
