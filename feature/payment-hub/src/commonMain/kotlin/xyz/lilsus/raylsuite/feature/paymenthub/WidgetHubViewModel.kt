package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.raylapi.UsageEventKind
import xyz.lilsus.raylsuite.core.widgetspec.WidgetPurchase as WidgetOffer
import xyz.lilsus.raylsuite.core.widgetspec.WidgetValidateResult
import xyz.lilsus.raylsuite.feature.paymenthub.create.cleanAmountInput
import xyz.lilsus.raylsuite.feature.paymenthub.create.formatMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.integration.widget.WidgetClient
import xyz.lilsus.raylsuite.integration.widget.WidgetDefinitionFetch

/** Shared widget presentation state. Native platforms own sheets, controls, gestures and copy. */
class WidgetHubViewModel(
    private val repository: PaymentHubRepository,
    private val host: PaymentHubController,
    private val defaultCurrencyCode: () -> String,
    private val widgets: WidgetClient = WidgetClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState =
        MutableStateFlow(WidgetHubState(gallery = LocalHubWidgets.definitions))
    val state = mutableState.asStateFlow()
    private var providerJob: Job? = null
    private var statusJob: Job? = null
    private val providerStatus = mutableMapOf<String, ProviderTileStatus>()
    private var statusWidgetIds = emptySet<String>()

    init {
        scope.launch { host.widgetLinks.collect(::startProviderSetup) }
        scope.launch {
            host.services.catalog.collect { catalog ->
                mutableState.update {
                    it.copy(catalog = catalog.catalogItems(), serviceCatalog = catalog)
                }
                rebuildTiles()
            }
        }
        scope.launch {
            repository.hub.collect { hub ->
                val editor = mutableState.value.editor
                val editedWidgetRemoved = editor?.existingWidgetId?.let { hub.widget(it) == null }
                    ?: false
                mutableState.update { current ->
                    current.copy(
                        contacts = hub.contacts,
                        defaultContactIds = hub.rankedContactIds(),
                        purchases = hub.purchases,
                        screen = if (editedWidgetRemoved) HubWidgetScreen.Hub else current.screen,
                        editor = if (editedWidgetRemoved) {
                            null
                        } else {
                            editor?.copy(
                                contactIds = editor.contactIds.filter {
                                    hub.contact(it) !=
                                        null
                                }
                            )
                        }
                    )
                }
                val withStatus = hub.widgets.filter { it.provider?.definition?.statusUrl != null }
                    .mapTo(mutableSetOf()) { it.id }
                if (withStatus != statusWidgetIds) {
                    statusWidgetIds = withStatus
                    providerStatus.keys.retainAll(withStatus)
                    refreshProviderStatus()
                }
                rebuildTiles()
            }
        }
    }

    fun openGallery() {
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Gallery,
                editor = null,
                providerSetup = null,
                query = "",
                error = null,
                arranging = false
            )
        }
        // The sheet is where a newly listed service is expected, so it asks sooner than the Hub.
        host.services.refresh(maxAgeMs = ADD_SHEET_REFRESH_MS)
    }

    fun close() {
        providerJob?.cancel()
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Hub,
                editor = null,
                query = "",
                error = null,
                providerSetup = null
            )
        }
    }

    /** Fetches a definition from a pasted or scanned link and opens its setup. */
    fun startProviderSetup(link: String) {
        val url = link.trim()
        providerJob?.cancel()
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.ProviderSetup,
                editor = null,
                providerSetup = ProviderSetupState(url, ProviderSetupPhase.Loading)
            )
        }
        if (host.services.blocks(url)) {
            mutableState.update {
                it.copy(
                    providerSetup = ProviderSetupState(
                        url,
                        ProviderSetupPhase.Failed(ProviderSetupFailure.Blocked)
                    )
                )
            }
            return
        }
        providerJob = scope.launch {
            val phase = when (val fetch = widgets.fetchDefinition(url)) {
                is WidgetDefinitionFetch.Loaded -> {
                    val definition = fetch.definition
                    ProviderSetupPhase.Families(
                        definition = definition,
                        body = fetch.body,
                        etag = fetch.etag,
                        selected = definition.supportedFamilies.first()
                    )
                }

                is WidgetDefinitionFetch.Invalid ->
                    ProviderSetupPhase.Failed(ProviderSetupFailure.Invalid(fetch.problems))

                WidgetDefinitionFetch.Gone -> ProviderSetupPhase.Failed(ProviderSetupFailure.Gone)

                WidgetDefinitionFetch.NotModified,
                WidgetDefinitionFetch.NotResponding ->
                    ProviderSetupPhase.Failed(ProviderSetupFailure.NotResponding)
            }
            mutableState.update { state ->
                state.copy(
                    providerSetup = state.providerSetup?.takeIf { it.url == url }
                        ?.copy(phase = phase)
                )
            }
        }
    }

    /** Opens a saved provider widget's setup to change its product, inputs or size. */
    fun editProviderWidget(id: String) {
        val widget = repository.hub.value.widget(id) ?: return
        val setup = widget.provider ?: return
        val definition = setup.definition ?: return
        providerJob?.cancel()
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.ProviderSetup,
                editor = null,
                providerSetup = ProviderSetupState(
                    url = setup.definitionUrl,
                    phase = ProviderSetupPhase.Ready(
                        definition = definition,
                        body = setup.definitionBody,
                        etag = setup.etag,
                        productIds = setup.productIds,
                        inputs = ProviderSecrets.inputsFor(setup),
                        size = widget.variant.providerSize()
                    ),
                    existingWidgetId = id
                )
            )
        }
    }

    fun resizeProviderWidget(id: String, size: ProviderWidgetSize) = mutate {
        val widget = repository.hub.value.widget(id) ?: return@mutate
        val setup = widget.provider ?: return@mutate
        val definition = setup.definition ?: return@mutate
        if (size !in definition.families) return@mutate
        // Resizing never drops the item a tap was buying; a larger tile adds the defaults.
        val productIds = (setup.productIds.take(1) + definition.defaultProducts(size))
            .distinct()
            .take(size.tileCapacity())
        val resized = setup.copy(
            productIds = productIds,
            pinnedPrices = productIds.mapNotNull(definition::product).associate {
                it.id to it.price
            }
        )
        repository.saveProviderWidget(resized, size.variant(), id)
    }

    /**
     * Continues from the family chooser with that family's defaults. Setup then only asks for
     * what the widget needs to work; a widget that needs nothing is added right away.
     */
    fun chooseProviderFamily(size: ProviderWidgetSize) {
        val families = mutableState.value.providerSetup?.phase as? ProviderSetupPhase.Families
        mutableState.update { state ->
            val setup = state.providerSetup ?: return@update state
            val families = setup.phase as? ProviderSetupPhase.Families ?: return@update state
            val definition = families.definition
            if (size !in definition.families) return@update state
            state.copy(
                providerSetup = setup.copy(
                    phase = ProviderSetupPhase.Ready(
                        definition = definition,
                        body = families.body,
                        etag = families.etag,
                        productIds = definition.defaultProducts(size),
                        inputs = definition.inputs.associate { it.id to "" },
                        size = size
                    )
                )
            )
        }
        val definition = families?.definition ?: return
        val hasDefault =
            definition.purchase !is WidgetOffer.Product ||
                definition.defaultProducts(size).isNotEmpty()
        if (definition.inputs.isEmpty() && definition.terms == null && hasDefault) {
            saveProviderWidget()
        }
    }

    /** Small widgets buy one product; larger ones show the selected products, in order. */
    fun selectProviderProduct(id: String) = editProvider { ready ->
        if (ready.definition.product(id)?.available != true) return@editProvider ready
        when {
            ready.size == ProviderWidgetSize.Small -> ready.copy(productIds = listOf(id))
            id in ready.productIds -> ready.copy(productIds = ready.productIds - id)
            ready.productIds.size >= ready.size.tileCapacity() -> ready
            else -> ready.copy(productIds = ready.productIds + id)
        }
    }

    fun updateProviderInput(id: String, value: String) =
        editProvider { it.copy(inputs = it.inputs + (id to value.take(2_048))) }

    fun selectProviderSize(size: ProviderWidgetSize) = editProvider { it.copy(size = size) }

    /**
     * Checks the inputs with the provider when it offers a read-only check, then saves. Tapping
     * save is the user's explicit acceptance of the terms shown.
     */
    fun saveProviderWidget() {
        val setup = mutableState.value.providerSetup ?: return
        val ready = setup.phase as? ProviderSetupPhase.Ready ?: return
        if (ready.validating) return
        val definition = ready.definition
        if (definition.purchase is WidgetOffer.Product && ready.productIds.isEmpty()) {
            return editProvider { it.copy(error = ProviderSetupError.ChooseProduct) }
        }
        val inputs = mutableMapOf<String, String>()
        for (input in definition.inputs) {
            val value = input.normalize(ready.inputs[input.id].orEmpty())
                ?: return editProvider {
                    it.copy(error = ProviderSetupError.InvalidInput(input.id))
                }
            if (value.isNotEmpty()) inputs[input.id] = value
        }
        val products = ready.productIds.mapNotNull(definition::product)
        editProvider { it.copy(validating = true) }
        providerJob = scope.launch {
            val validation = widgets.validate(definition, inputs, products.firstOrNull()?.id)
            if (validation is WidgetValidateResult.Refused) {
                val refused = validation.refusal
                editProvider {
                    it.copy(
                        validating = false,
                        error =
                            refused.inputId?.takeIf { id ->
                                definition.inputs.any { input ->
                                    input.id ==
                                        id
                                }
                            }
                                ?.let { id -> ProviderSetupError.InvalidInput(id, refused.message) }
                                ?: ProviderSetupError.Rejected(refused.message)
                    )
                }
                return@launch
            }
            val secretIds = definition.inputs.filter { it.secret }.map { it.id }.toSet()
            secretIds.forEach { id ->
                inputs[id]?.let { ProviderSecrets.put(definition.source.value, id, it) }
            }
            val providerSetup = ProviderWidgetSetup(
                definitionUrl = definition.source.value,
                definitionBody = ready.body,
                etag = ready.etag,
                productIds = products.map { it.id },
                pinnedPrices = products.associate { it.id to it.price },
                inputs = inputs.filterKeys { it !in secretIds },
                acceptedTermsVersion = definition.terms?.version,
                accountHint = (validation as? WidgetValidateResult.Valid)?.message
            )
            if (repository.saveProviderWidget(
                    providerSetup,
                    ready.size.variant(),
                    setup.existingWidgetId
                ) == null
            ) {
                editProvider { it.copy(validating = false, error = ProviderSetupError.SaveFailed) }
            } else {
                if (setup.existingWidgetId == null) {
                    host.services.report(providerSetup.definitionUrl, UsageEventKind.Added)
                }
                close()
            }
        }
    }

    fun payProvider(widgetId: String, productId: String? = null, amount: StoredAmount? = null) {
        if (mutableState.value.arranging) return
        host.dispatch(PaymentHubIntent.PayWidget(widgetId, productId, amount))
    }

    /** Refreshes provider status values now, for example on pull to refresh. */
    fun refreshProviderStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            val widgetsWithStatus = repository.hub.value.widgets.filter {
                it.provider?.definition?.statusUrl != null
            }
            if (widgetsWithStatus.isEmpty()) return@launch
            var next = 900
            for (widget in widgetsWithStatus) {
                val setup = widget.provider ?: continue
                val definition = setup.definition ?: continue
                providerStatus[widget.id] =
                    (providerStatus[widget.id] ?: ProviderTileStatus(null, false, false))
                        .copy(loading = true)
                rebuildTiles()
                val value = widgets.fetchStatus(definition, ProviderSecrets.inputsFor(setup))
                val previous = providerStatus[widget.id]?.value
                providerStatus[widget.id] =
                    ProviderTileStatus(value ?: previous, loading = false, stale = value == null)
                value?.let { next = minOf(next, it.refreshAfterSeconds) }
                rebuildTiles()
            }
            delay(next.coerceAtLeast(MIN_STATUS_REFRESH_SECONDS) * 1_000L)
            refreshProviderStatus()
        }
    }

    private fun editProvider(transform: (ProviderSetupPhase.Ready) -> ProviderSetupPhase.Ready) {
        mutableState.update { state ->
            val setup = state.providerSetup ?: return@update state
            val ready = setup.phase as? ProviderSetupPhase.Ready ?: return@update state
            state.copy(providerSetup = setup.copy(phase = transform(ready.copy(error = null))))
        }
    }

    fun back(): Boolean {
        when (mutableState.value.screen) {
            HubWidgetScreen.Hub -> {
                if (!mutableState.value.arranging) return false
                setArranging(false)
            }

            HubWidgetScreen.Gallery -> close()

            HubWidgetScreen.Variants -> {
                if (mutableState.value.editor?.existingWidgetId != null) close() else openGallery()
            }

            HubWidgetScreen.Configure -> mutableState.update {
                it.copy(screen = HubWidgetScreen.Variants, error = null)
            }

            HubWidgetScreen.ProviderSetup -> {
                val setup = mutableState.value.providerSetup
                val ready = setup?.phase as? ProviderSetupPhase.Ready
                if (setup?.existingWidgetId == null && ready != null) {
                    mutableState.update {
                        it.copy(
                            providerSetup = setup.copy(
                                phase = ProviderSetupPhase.Families(
                                    ready.definition,
                                    ready.body,
                                    ready.etag,
                                    ready.size
                                )
                            )
                        )
                    }
                } else if (setup?.existingWidgetId != null) {
                    close()
                } else {
                    openGallery()
                }
            }
        }
        return true
    }

    fun selectDefinition(id: String) {
        val definition = mutableState.value.gallery.firstOrNull { it.id == id } ?: return
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Variants,
                query = "",
                error = null,
                editor = HubWidgetEditor(
                    id,
                    definition.kind,
                    definition.variants.first().id,
                    contactIds = it.defaultContacts(definition.kind, definition.variants.first()),
                    currencyCode = defaultCurrencyCode()
                )
            )
        }
    }

    fun selectVariant(id: String) {
        val definition = mutableState.value.selectedDefinition ?: return
        val variant = definition.variants.firstOrNull { it.id == id } ?: return
        edit {
            it.copy(
                variantId = id,
                contactIds = if (it.existingWidgetId == null) {
                    mutableState.value.defaultContacts(it.kind, variant)
                } else {
                    it.contactIds.take(variant.capacity)
                }
            )
        }
    }

    /**
     * Continues from the size carousel. Like provider widgets, a widget that already has what it
     * needs goes straight onto the Hub with its defaults; setup only asks for what is missing.
     */
    fun configureSelected() {
        val editor = mutableState.value.editor ?: return
        if (editor.addsWithDefaults()) return saveWidget()
        mutableState.update {
            it.copy(screen = HubWidgetScreen.Configure, query = "", error = null)
        }
    }

    /** Resizes in place, like provider widgets: a larger contacts tile fills up with suggestions. */
    fun resizeWidget(id: String, variantId: String) {
        val hub = repository.hub.value
        val widget = hub.widget(id) ?: return
        if (widget.kind == HubWidgetKind.Provider || widget.kind == HubWidgetKind.Shortcut) return
        val definition =
            LocalHubWidgets.definitions.firstOrNull { it.id == widget.definitionId } ?: return
        val variant = definition.variants.firstOrNull { it.id == variantId } ?: return
        val contactIds = if (widget.kind == HubWidgetKind.Contacts) {
            (widget.contactIds + hub.rankedContactIds()).distinct().take(variant.capacity)
        } else {
            emptyList()
        }
        val draft = HubWidgetDraft(widget.definitionId, widget.kind, variant, contactIds)
        mutate { if (repository.saveWidget(draft, id) == null) fail(HubWidgetError.SaveFailed) }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }
    fun updateComment(value: String) = edit { it.copy(comment = value.take(500)) }

    fun toggleContact(id: String) {
        if (repository.hub.value.contact(id) == null) return
        val capacity = mutableState.value.selectedVariant?.capacity ?: return
        val current = mutableState.value.editor ?: return
        if (capacity > 1 && id !in current.contactIds && current.contactIds.size >= capacity) {
            return fail(HubWidgetError.TooManyContacts)
        }
        edit { editor ->
            if (capacity == 1) {
                editor.copy(contactIds = listOf(id))
            } else {
                editor.copy(
                    contactIds = if (id in
                        editor.contactIds
                    ) {
                        editor.contactIds - id
                    } else {
                        editor.contactIds + id
                    }
                )
            }
        }
    }

    fun moveContact(id: String, delta: Int) {
        edit { editor ->
            val from = editor.contactIds.indexOf(id)
            if (from < 0) {
                editor
            } else {
                val reordered = editor.contactIds.toMutableList()
                reordered.removeAt(from)
                reordered.add((from + delta).coerceIn(0, reordered.size), id)
                editor.copy(contactIds = reordered)
            }
        }
    }

    fun updateAmount(value: String) = edit {
        val digits = CurrencyCatalog.infoFor(it.currencyCode).fractionDigits
        it.copy(
            amountInput = if (value.hasFractionForWholeCurrency(
                    digits
                )
            ) {
                value
            } else {
                value.cleanAmountInput(digits)
            }
        )
    }

    fun selectCurrency(code: String) {
        if (code !in CurrencyCatalog.supportedCodes) return
        edit { it.copy(currencyCode = code, amountInput = "") }
    }

    fun addContact(title: String, address: String) {
        if (mutableState.value.busy) return
        val name = title.trim()
        if (name.isEmpty()) return fail(HubWidgetError.ContactNameRequired)
        val parsed = LightningAddress.parse(address) ?: return fail(HubWidgetError.InvalidAddress)
        val existing = repository.hub.value.contacts.firstOrNull {
            it.address.isSameAddressAs(parsed)
        }
        val editor = mutableState.value.editor
        val capacity = mutableState.value.selectedVariant?.capacity ?: 1
        if (capacity > 1 && editor?.kind == HubWidgetKind.Contacts &&
            existing?.id !in editor.contactIds &&
            editor.contactIds.size >= capacity
        ) {
            return fail(HubWidgetError.TooManyContacts)
        }
        mutate {
            val contact = existing ?: repository.saveContact(parsed, name)
            mutableState.update { current ->
                val currentEditor = current.editor
                current.copy(
                    contactSavedSerial = current.contactSavedSerial + 1,
                    editor = currentEditor?.copy(
                        contactIds = if (capacity == 1) {
                            listOf(contact.id)
                        } else {
                            (currentEditor.contactIds + contact.id).distinct()
                        }
                    )
                )
            }
        }
    }

    fun deleteContact(id: String) = mutate { repository.deleteContact(id) }

    fun saveWidget() {
        val snapshot = mutableState.value
        val editor = snapshot.editor ?: return
        val variant = snapshot.selectedVariant ?: return fail(HubWidgetError.Unavailable)
        snapshot.editorValidationError()?.let { return fail(it) }
        val amount = if (editor.kind == HubWidgetKind.Shortcut) {
            editor.shortcutAmount()
        } else {
            null
        }
        val draft = HubWidgetDraft(
            editor.definitionId,
            editor.kind,
            variant,
            editor.contactIds,
            amount,
            editor.comment
        )
        mutate {
            if (repository.saveWidget(draft, editor.existingWidgetId) == null) {
                fail(HubWidgetError.SaveFailed)
            } else {
                close()
            }
        }
    }

    fun editWidget(id: String) {
        val hub = repository.hub.value
        val widget = hub.widget(id) ?: return
        val definition = mutableState.value.gallery.firstOrNull { it.id == widget.definitionId }
        if (definition == null) {
            fail(HubWidgetError.Unavailable)
            return
        }
        val variant = definition.variants.firstOrNull { it.id == widget.variant.id }
            ?: definition.variants.first()
        val target = widget.targetId?.let(hub::target)
        val amount = (target?.amountRule as? DirectTargetAmountRule.Preset)?.amount
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Configure,
                query = "",
                error = null,
                editor = HubWidgetEditor(
                    widget.definitionId,
                    widget.kind,
                    variant.id,
                    widget.id,
                    contactIds = target?.let { listOf(it.contactId) } ?: widget.contactIds,
                    amountInput = amount?.minor?.formatMinorAmount(
                        CurrencyCatalog.infoFor(amount.normalizedCurrencyCode).fractionDigits
                    ).orEmpty(),
                    currencyCode = amount?.normalizedCurrencyCode ?: defaultCurrencyCode(),
                    comment = target?.comment.orEmpty()
                )
            )
        }
    }

    fun removeWidget(id: String) = mutate {
        val definitionUrl = repository.hub.value.widget(id)?.provider?.definitionUrl
        repository.deleteWidget(id)
        definitionUrl?.let { host.services.report(it, UsageEventKind.Removed) }
        if (mutableState.value.editor?.existingWidgetId == id) close()
    }
    fun moveWidget(id: String, targetId: String) {
        val index = repository.hub.value.widgets.indexOfFirst { it.id == targetId }
        if (index >= 0) mutate { repository.moveWidget(id, index) }
    }
    fun moveWidgetBy(id: String, delta: Int) {
        val index = repository.hub.value.widgets.indexOfFirst { it.id == id }
        if (index >= 0) mutate { repository.moveWidget(id, index + delta) }
    }
    fun setArranging(value: Boolean) {
        mutableState.update { it.copy(arranging = value, error = null) }
    }
    fun pay(actionId: String) {
        if (!mutableState.value.arranging && actionId.isNotBlank()) {
            host.dispatch(PaymentHubIntent.SelectItem(HubItemId(actionId)))
        }
    }

    fun clear() = scope.cancel()

    private fun edit(transform: (HubWidgetEditor) -> HubWidgetEditor) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(editor = it.editor?.let(transform), error = null) }
    }

    private fun fail(error: HubWidgetError) {
        mutableState.update { it.copy(error = error) }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(HubWidgetError.SaveFailed)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun rebuildTiles() {
        val hub = repository.hub.value
        val paid = hub.targets.filter { it.stats.successfulPaymentCount > 0 }
        val favorites = paid.sortedWith(
            compareByDescending<DirectPaymentTarget> {
                it.stats.successfulPaymentCount
            }
                .thenByDescending { it.stats.lastSuccessfulPaymentAtMs ?: 0 }.thenBy { it.id.value }
        )
        val recents = paid.sortedWith(
            compareByDescending<DirectPaymentTarget> {
                it.stats.lastSuccessfulPaymentAtMs
                    ?: 0
            }
                .thenBy { it.id.value }
        )
        val tiles = hub.widgets.map { widget ->
            val targets = when (widget.kind) {
                HubWidgetKind.Contacts -> widget.contactIds.mapNotNull(hub::contactTarget)
                HubWidgetKind.Shortcut -> listOfNotNull(widget.targetId?.let(hub::target))
                HubWidgetKind.Favorites -> favorites.take(widget.variant.capacity)
                HubWidgetKind.Recents -> recents.take(widget.variant.capacity)
                HubWidgetKind.Provider -> emptyList()
            }
            HubWidgetTile(
                id = widget.id,
                definitionId = widget.definitionId,
                kind = widget.kind,
                variant = widget.variant,
                provider = widget.providerTile(
                    hub.purchases,
                    providerStatus[widget.id],
                    host.services.catalog.value
                ),
                people = targets.mapNotNull { target ->
                    hub.contact(target.contactId)?.let { contact ->
                        HubWidgetPerson(
                            target.id.value,
                            contact.id,
                            // A shortcut is labelled by what it is for, when its note says so.
                            target.comment?.takeIf { widget.kind == HubWidgetKind.Shortcut }
                                ?: contact.title,
                            contact.address.full,
                            (target.amountRule as? DirectTargetAmountRule.Preset)?.amount,
                            target.stats.lastPaidSats.takeIf {
                                widget.kind == HubWidgetKind.Contacts
                            }
                                ?.let { StoredAmount(it, "SAT") }
                        )
                    }
                }
            )
        }
        mutableState.update { it.copy(widgets = tiles) }
    }

    private companion object {
        const val MIN_STATUS_REFRESH_SECONDS = 30
        const val ADD_SHEET_REFRESH_MS = 30_000L
    }
}

/** New contacts widgets start with the best suggestions; a shortcut preselects the top one. */
private fun WidgetHubState.defaultContacts(
    kind: HubWidgetKind,
    variant: HubWidgetVariant
): List<String> = when (kind) {
    HubWidgetKind.Contacts -> defaultContactIds.take(variant.capacity)
    HubWidgetKind.Shortcut -> defaultContactIds.take(1)
    else -> emptyList()
}

/** Contacts need nothing more than their defaults; a shortcut still needs its amount. */
internal fun HubWidgetEditor.addsWithDefaults(): Boolean = existingWidgetId == null && when (kind) {
    HubWidgetKind.Contacts -> contactIds.isNotEmpty()
    HubWidgetKind.Favorites, HubWidgetKind.Recents -> true
    else -> false
}
