package xyz.lilsus.flint.feature.payment

import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.flint.application.payment.AmountRequiredPayment
import xyz.lilsus.flint.application.payment.ClaimedPaymentLink
import xyz.lilsus.flint.application.payment.ConfirmPaymentResult
import xyz.lilsus.flint.application.payment.LnurlPayReviewDetails
import xyz.lilsus.flint.application.payment.PaymentActivity
import xyz.lilsus.flint.application.payment.PaymentConfirmationMode as FlintConfirmationMode
import xyz.lilsus.flint.application.payment.PaymentConfirmationPolicy
import xyz.lilsus.flint.application.payment.PaymentEngine
import xyz.lilsus.flint.application.payment.PaymentLinkInbox
import xyz.lilsus.flint.application.payment.PaymentOrigin
import xyz.lilsus.flint.application.payment.PaymentOutcome
import xyz.lilsus.flint.application.payment.PaymentRejection
import xyz.lilsus.flint.application.payment.PreparePaymentResult
import xyz.lilsus.flint.application.payment.PreparedPayment
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.model.Satoshi
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymentcurrency.CurrencyManagerError
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule
import xyz.lilsus.raylsuite.feature.paymenthub.host.DirectTargetPaymentIntent
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountConfig
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountController
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

class PaymentCoordinator(
    private val engine: PaymentEngine,
    private val paymentLinks: PaymentLinkInbox,
    bitcoinPriceProvider: BitcoinPriceProvider,
    private val currencyPreferences: CurrencyPreferences,
    private val paymentPreferences: PaymentPreferencesRepository,
    private val paymentHub: PaymentHubController,
    private val haptics: HapticFeedbackManager,
    coroutineContext: CoroutineContext = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val actionMutex = Mutex()
    private val currencyManager = PaymentCurrencyManager(bitcoinPriceProvider, scope)
    private val manualAmount =
        ManualAmountController(
            ManualAmountConfig(
                info = CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE),
                exchangeRate = null
            )
        )
    private val hubContexts = mutableMapOf<String, HubTargetContext>()

    private val mutableUiState = MutableStateFlow<PaymentUiState>(PaymentUiState.Active)
    val uiState: StateFlow<PaymentUiState> = mutableUiState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PaymentEvent> = mutableEvents.asSharedFlow()

    private val mutableSessionTransactions =
        MutableStateFlow<List<SessionTransactionItem>>(emptyList())
    val sessionTransactions: StateFlow<List<SessionTransactionItem>> =
        mutableSessionTransactions.asStateFlow()

    private val mutableTransactionDetailNavigationTarget = MutableStateFlow<String?>(null)
    val transactionDetailNavigationTarget: StateFlow<String?> =
        mutableTransactionDetailNavigationTarget.asStateFlow()

    private val mutableNewSessionTransactionCount = MutableStateFlow(0)
    val newSessionTransactionCount: StateFlow<Int> =
        mutableNewSessionTransactionCount.asStateFlow()

    private val sessionAttemptIds = linkedSetOf<String>()
    private val knownSessionTransactionIds = mutableSetOf<String>()
    private val newSessionTransactionIds = mutableSetOf<String>()
    private val terminalHubUpdates = mutableSetOf<String>()
    private val successNotifications = mutableSetOf<String>()
    private val interaction = PaymentInteractionSession()
    private var activeLinkId: String? = null
    private var vibrateOnScan = true
    private var vibrateOnPayment = true
    private var offerToSaveNewTargets = true
    private var pendingPresentationJob: Job? = null

    init {
        scope.launch {
            paymentPreferences.preferences.collectLatest { preferences ->
                vibrateOnScan = preferences.vibrateOnScan
                vibrateOnPayment = preferences.vibrateOnPayment
                offerToSaveNewTargets = preferences.offerToSaveNewTargets
                engine.updateConfirmationPolicy(preferences.toFlintConfirmationPolicy())
            }
        }
        scope.launch {
            currencyPreferences.code.collectLatest { code ->
                currencyManager.setPreferredCurrency(CurrencyCatalog.infoFor(code).currency)
            }
        }
        scope.launch {
            currencyManager.state.collectLatest {
                refreshCurrencyDependentPresentation()
            }
        }
        scope.launch {
            currencyManager.errors.collectLatest {
                mutableEvents.emit(PaymentEvent.ShowError(it.toPaymentUiError()))
            }
        }
        scope.launch {
            engine.activity.collectLatest(::handleActivityUpdate)
        }
        scope.launch {
            paymentHub.paymentRequests.collect(::payTarget)
        }
        scope.launch {
            paymentLinks.revision.collect {
                actionMutex.withLock { handlePendingPaymentLink() }
            }
        }
    }

    fun dispatch(intent: PaymentIntent) {
        scope.launch {
            actionMutex.withLock {
                try {
                    handleIntent(intent)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    showError(
                        PaymentUiError.Spark(
                            SparkPaymentError.Unexpected(cause.message)
                        )
                    )
                }
            }
        }
    }

    fun clear() {
        pendingPresentationJob?.cancel()
        scope.cancel()
    }

    fun resetSession() {
        pendingPresentationJob?.cancel()
        sessionAttemptIds.clear()
        knownSessionTransactionIds.clear()
        newSessionTransactionIds.clear()
        terminalHubUpdates.clear()
        hubContexts.clear()
        successNotifications.clear()
        interaction.reset()
        mutableSessionTransactions.value = emptyList()
        mutableNewSessionTransactionCount.value = 0
        mutableTransactionDetailNavigationTarget.value = null
        paymentHub.resetSession()
        mutableUiState.value = PaymentUiState.Active
    }

    private suspend fun handleIntent(intent: PaymentIntent) {
        when (intent) {
            PaymentIntent.DismissResult -> dismissResult()

            is PaymentIntent.TransactionDetailNavigationHandled ->
                transactionDetailNavigationHandled(intent.id)

            PaymentIntent.SessionTransactionsOpened -> sessionTransactionsOpened()

            is PaymentIntent.QrCodeScanned ->
                handlePaymentInput(
                    rawInput = intent.rawValue,
                    origin = PaymentOrigin.DETECTED_CONTENT,
                    notifyScan = true
                )

            is PaymentIntent.DeepLinkReceived ->
                handlePaymentInput(intent.rawValue, PaymentOrigin.DEEP_LINK)

            is PaymentIntent.RawInputSubmitted ->
                handlePaymentInput(intent.rawValue, PaymentOrigin.DETECTED_CONTENT)

            PaymentIntent.ManualAmountDismiss -> dismissManualAmount()

            PaymentIntent.ManualAmountSubmit -> submitManualAmount()

            is PaymentIntent.ManualAmountKeyPress -> updateManualAmount(intent.key)

            PaymentIntent.ConfirmPaymentDismiss -> dismissConfirmation()

            PaymentIntent.ConfirmPaymentSubmit -> submitConfirmation()

            PaymentIntent.PendingRetryCreateNewInvoice,
            PaymentIntent.PendingRetryRetryPrevious,
            PaymentIntent.PendingRetryDismiss -> dismissResult()

            PaymentIntent.PendingRetryViewPending -> {
                val id = (mutableUiState.value as? PaymentUiState.PendingRetry)?.id
                if (id != null) requestTransactionDetailNavigation(id)
            }

            is PaymentIntent.RetryTransaction -> retryTransaction(intent.id)
        }
    }

    private suspend fun handlePaymentInput(
        rawInput: String,
        origin: PaymentOrigin,
        notifyScan: Boolean = false,
        targetContext: HubTargetContext? = reusableLightningAddress(rawInput)?.let {
            HubTargetContext(targetId = null, address = it, isPreset = false)
        },
        requestedAmountMsats: Long? = null,
        paymentQuote: PaymentAmountQuote? = null
    ) {
        if (mutableUiState.value != PaymentUiState.Active) return
        clearTransientPaymentState()
        if (notifyScan && vibrateOnScan) ignoreHapticFailure(haptics::notifyScanSuccess)
        mutableUiState.value = PaymentUiState.Detected
        mutableUiState.value = PaymentUiState.Loading(LoadingKind.Resolving)
        applyPrepareResult(
            result = engine.prepare(rawInput, origin),
            targetContext = targetContext,
            requestedAmountMsats = requestedAmountMsats,
            paymentQuote = paymentQuote
        )
    }

    private suspend fun applyPrepareResult(
        result: PreparePaymentResult,
        targetContext: HubTargetContext?,
        requestedAmountMsats: Long?,
        paymentQuote: PaymentAmountQuote? = null,
        amountEnteredManually: Boolean = false
    ) {
        when (result) {
            is PreparePaymentResult.AmountRequired -> {
                val lnurlPayDisplay =
                    result.payment.lnurlPayDetails?.toDisplay() ?: run {
                        if (result.payment.lnurlPayDetails != null) {
                            engine.cancel(result.payment.handle)
                            showError(
                                PaymentUiError.InvalidInvoice(
                                    "LNURL payment details are invalid"
                                )
                            )
                            return
                        }
                        null
                    }
                val request = ManualRequest(result.payment, targetContext, lnurlPayDisplay)
                interaction.requestManualAmount(request)
                if (requestedAmountMsats != null) {
                    prepareRequestedAmount(
                        result.payment,
                        requestedAmountMsats,
                        targetContext,
                        paymentQuote
                    )
                } else {
                    showManualAmount(request, clearInput = true)
                }
            }

            is PreparePaymentResult.Ready ->
                handlePreparedPayment(
                    result.payment,
                    targetContext,
                    paymentQuote,
                    amountEnteredManually
                )

            is PreparePaymentResult.Existing -> {
                sessionAttemptIds += result.activity.attemptId
                refreshSessionTransactions(engine.activity.value)
                showActivity(result.activity, wasAlreadyPaid = true)
            }

            is PreparePaymentResult.Rejected ->
                showError(result.reason.toPaymentUiError())

            PreparePaymentResult.WalletUnavailable ->
                showError(PaymentUiError.Spark(SparkPaymentError.WalletUnavailable))

            PreparePaymentResult.SdkFailure ->
                showError(PaymentUiError.Spark(SparkPaymentError.SdkUnavailable))

            PreparePaymentResult.StorageFailure ->
                showError(PaymentUiError.Spark(SparkPaymentError.StorageUnavailable))
        }
    }

    private suspend fun handlePreparedPayment(
        payment: PreparedPayment,
        targetContext: HubTargetContext?,
        paymentQuote: PaymentAmountQuote?,
        amountEnteredManually: Boolean
    ) {
        interaction.reset()
        val lnurlPayDisplay = payment.lnurlPayDetails?.toDisplay()
        if (payment.lnurlPayDetails != null && lnurlPayDisplay == null) {
            engine.cancel(payment.handle)
            showError(PaymentUiError.InvalidInvoice("LNURL payment details are invalid"))
            return
        }
        if (
            paymentQuote != null &&
            payment.amountSats.toMsats() != paymentQuote.amountMsats
        ) {
            engine.cancel(payment.handle)
            showError(PaymentUiError.InvalidInvoice("Quoted amount does not match payment"))
            return
        }
        // The Spark engine policy knows nothing about hub targets. Preset amounts always
        // require review because their resolved amount can depend on an exchange rate.
        val requiresConfirmation = requiresPreparedPaymentConfirmation(
            engineRequiresConfirmation = payment.requiresConfirmation,
            isPresetTarget = targetContext?.isPreset == true,
            amountEnteredManually = amountEnteredManually
        )
        val confirmationAmount =
            if (requiresConfirmation) {
                confirmationAmount(payment.amountSats.toMsats(), paymentQuote)
            } else {
                null
            }
        val draft = ActiveDraft(
            payment,
            targetContext,
            paymentQuote,
            confirmationAmount,
            lnurlPayDisplay
        )
        interaction.prepareDraft(draft)
        mutableUiState.value =
            if (requiresConfirmation) {
                PaymentUiState.Confirm(requireNotNull(confirmationAmount), lnurlPayDisplay)
            } else {
                PaymentUiState.Loading()
            }
        if (!requiresConfirmation) {
            // Manual submission already authorizes this amount, regardless of scan policy.
            val result = if (amountEnteredManually) {
                engine.confirm(payment.handle)
            } else {
                engine.autoPay(payment.handle)
            }
            applyConfirmResult(result, draft)
        }
    }

    private suspend fun submitConfirmation() {
        val draft = interaction.activeDraft ?: return
        mutableUiState.value = PaymentUiState.Loading()
        applyConfirmResult(engine.confirm(draft.payment.handle), draft)
    }

    private suspend fun applyConfirmResult(result: ConfirmPaymentResult, draft: ActiveDraft) {
        when (result) {
            is ConfirmPaymentResult.Submitted -> {
                interaction.beginAttempt(result.activity.attemptId)
                sessionAttemptIds += result.activity.attemptId
                draft.targetContext?.let { hubContexts[result.activity.attemptId] = it }
                refreshSessionTransactions(engine.activity.value)
                showActivity(result.activity)
            }

            ConfirmPaymentResult.ConfirmationRequired -> {
                val confirmationAmount =
                    draft.confirmationAmount
                        ?: confirmationAmount(
                            draft.payment.amountSats.toMsats(),
                            draft.paymentQuote
                        )
                interaction.prepareDraft(draft.copy(confirmationAmount = confirmationAmount))
                mutableUiState.value =
                    PaymentUiState.Confirm(confirmationAmount, draft.lnurlPayDisplay)
            }

            ConfirmPaymentResult.DraftUnavailable ->
                showError(PaymentUiError.InvalidInvoice("The payment request expired"))

            ConfirmPaymentResult.WalletUnavailable ->
                showError(PaymentUiError.Spark(SparkPaymentError.WalletUnavailable))

            ConfirmPaymentResult.PersistenceFailed ->
                showError(PaymentUiError.Spark(SparkPaymentError.StorageUnavailable))

            ConfirmPaymentResult.CapacityReached ->
                showError(PaymentUiError.Spark(SparkPaymentError.CapacityReached))
        }
    }

    private suspend fun dismissManualAmount() {
        interaction.manualRequest?.payment?.handle?.let { engine.cancel(it) }
        finishPaymentInteraction()
    }

    private suspend fun dismissConfirmation() {
        interaction.activeDraft?.payment?.handle?.let { engine.cancel(it) }
        finishPaymentInteraction()
    }

    private fun updateManualAmount(key: ManualAmountKey) {
        val state = mutableUiState.value as? PaymentUiState.EnterAmount ?: return
        mutableUiState.value =
            PaymentUiState.EnterAmount(
                manualAmount.handleKeyPress(key),
                state.lnurlPayDisplay
            )
    }

    private suspend fun submitManualAmount() {
        val entryState = mutableUiState.value as? PaymentUiState.EnterAmount ?: return
        val request = interaction.manualRequest ?: return
        val enteredAmount = manualAmount.enteredAmount() ?: return
        mutableUiState.value = PaymentUiState.Loading(LoadingKind.Resolving)
        val paymentQuote = currencyManager.quote(enteredAmount)
        val sats = paymentQuote?.amountMsats?.let(::msatsToSatoshi)
        if (paymentQuote == null || sats == null) {
            mutableUiState.value = entryState
            val error = when (val currency = enteredAmount.currency) {
                is DisplayCurrency.Fiat ->
                    PaymentUiError.ExchangeRateUnavailable(currency.iso4217)

                else -> PaymentUiError.InvalidInvoice("Amount could not be converted")
            }
            mutableEvents.tryEmit(PaymentEvent.ShowError(error))
            return
        }
        applyPrepareResult(
            engine.prepareAmount(request.payment.handle, sats),
            request.targetContext,
            requestedAmountMsats = null,
            paymentQuote = paymentQuote,
            amountEnteredManually = true
        )
    }

    private suspend fun prepareRequestedAmount(
        payment: AmountRequiredPayment,
        amountMsats: Long,
        targetContext: HubTargetContext?,
        paymentQuote: PaymentAmountQuote? = null
    ) {
        val sats = msatsToSatoshi(amountMsats)
        if (
            sats == null ||
            sats.value < payment.minimumAmountSats.value ||
            payment.maximumAmountSats?.let { sats.value > it.value } == true
        ) {
            showError(PaymentUiError.InvalidInvoice("Amount is outside the allowed range"))
            return
        }
        applyPrepareResult(
            engine.prepareAmount(payment.handle, sats),
            targetContext,
            requestedAmountMsats = null,
            paymentQuote = paymentQuote
        )
    }

    private fun showManualAmount(request: ManualRequest, clearInput: Boolean) {
        val payment = request.payment
        currencyManager.ensureExchangeRateIfNeeded()
        val currencyState = currencyManager.state.value
        val minMsats = payment.minimumAmountSats.toMsats()
        val maxMsats = payment.maximumAmountSats?.toMsats()
        mutableUiState.value =
            PaymentUiState.EnterAmount(
                manualAmount.reset(
                    config =
                        ManualAmountConfig(
                            info = currencyState.info,
                            exchangeRate = currencyState.exchangeRate,
                            minMsats = minMsats,
                            maxMsats = maxMsats
                        ),
                    clearInput = clearInput
                ),
                request.lnurlPayDisplay
            )
    }

    private fun handleActivityUpdate(activity: List<PaymentActivity>) {
        refreshSessionTransactions(activity)
        activity.forEach(::updateTerminalHubState)
        val active = interaction.activeAttemptId?.let { id ->
            activity.firstOrNull { it.attemptId == id }
        }
        if (active != null) {
            when (active.outcome) {
                PaymentOutcome.COMPLETED,
                PaymentOutcome.FAILED,
                PaymentOutcome.SUBMISSION_UNRESOLVED,
                PaymentOutcome.STATUS_UNAVAILABLE -> showActivity(active)

                else -> Unit
            }
        }
    }

    private fun showActivity(activity: PaymentActivity, wasAlreadyPaid: Boolean = false) {
        interaction.showActivity(VisibleActivity(activity, wasAlreadyPaid))
        pendingPresentationJob?.cancel()
        when (activity.outcome) {
            PaymentOutcome.COMPLETED -> {
                notifyPaymentSuccess(activity.attemptId)
                mutableUiState.value =
                    PaymentUiState.Success(
                        amountPaid = display(activity.amountSats),
                        feePaid = display(activity.feeSats),
                        wasAlreadyPaid = wasAlreadyPaid
                    )
            }

            PaymentOutcome.FAILED ->
                showError(
                    PaymentUiError.Spark(
                        SparkPaymentError.Rejected("The Spark payment failed")
                    ),
                    keepVisibleActivity = true
                )

            PaymentOutcome.SUBMISSION_UNRESOLVED,
            PaymentOutcome.STATUS_UNAVAILABLE ->
                showError(
                    PaymentUiError.Spark(
                        SparkPaymentError.OutcomeUnknown(
                            "The payment may still complete"
                        )
                    ),
                    keepVisibleActivity = true
                )

            PaymentOutcome.CONFIRMATION_RECORDED,
            PaymentOutcome.PENDING -> {
                mutableUiState.value = PaymentUiState.Loading()
                pendingPresentationJob =
                    scope.launch {
                        delay(PENDING_PRESENTATION_TIMEOUT_MS)
                        if (
                            interaction.activeAttemptId == activity.attemptId &&
                            mutableUiState.value is PaymentUiState.Loading
                        ) {
                            mutableUiState.value = PaymentUiState.Active
                        }
                    }
            }
        }
    }

    private fun refreshSessionTransactions(activity: List<PaymentActivity>) {
        val items =
            activity
                .asSequence()
                .filter { it.attemptId in sessionAttemptIds }
                .map { it.toSessionTransactionItem() }
                .toList()
        mutableSessionTransactions.value = items
        val unseen = items.map { it.id }.filterNot(knownSessionTransactionIds::contains)
        if (unseen.isNotEmpty()) {
            knownSessionTransactionIds += unseen
            newSessionTransactionIds += unseen
            mutableNewSessionTransactionCount.value = newSessionTransactionIds.size
        }
    }

    private fun PaymentActivity.toSessionTransactionItem(): SessionTransactionItem {
        val error =
            when (outcome) {
                PaymentOutcome.SUBMISSION_UNRESOLVED,
                PaymentOutcome.STATUS_UNAVAILABLE ->
                    PaymentUiError.Spark(
                        SparkPaymentError.OutcomeUnknown("The payment may still complete")
                    )

                PaymentOutcome.FAILED ->
                    PaymentUiError.Spark(
                        SparkPaymentError.Rejected("The Spark payment failed")
                    )

                else -> null
            }
        return SessionTransactionItem(
            id = attemptId,
            amount = display(amountSats),
            status =
                when (outcome) {
                    PaymentOutcome.CONFIRMATION_RECORDED -> PendingStatus.Resolving

                    PaymentOutcome.PENDING -> PendingStatus.Sending

                    PaymentOutcome.SUBMISSION_UNRESOLVED,
                    PaymentOutcome.STATUS_UNAVAILABLE -> PendingStatus.OutcomeUnknown

                    PaymentOutcome.COMPLETED -> PendingStatus.Succeeded

                    PaymentOutcome.FAILED -> PendingStatus.Failed
                },
            createdAtMs = createdAtEpochSeconds * MILLIS_PER_SECOND,
            resultAmount = display(amountSats),
            fee = display(feeSats),
            error = error
        )
    }

    private fun updateTerminalHubState(activity: PaymentActivity) {
        if (activity.attemptId in terminalHubUpdates) return
        when (activity.outcome) {
            PaymentOutcome.COMPLETED -> {
                terminalHubUpdates += activity.attemptId
                val context = hubContexts.remove(activity.attemptId) ?: return
                val targetId = context.targetId
                if (targetId != null) {
                    paymentHub.recordSuccessfulPayment(targetId)
                } else if (offerToSaveNewTargets) {
                    paymentHub.offerSave(context.address)
                }
            }

            PaymentOutcome.FAILED -> {
                terminalHubUpdates += activity.attemptId
                hubContexts.remove(activity.attemptId)
            }

            else -> Unit
        }
    }

    private fun notifyPaymentSuccess(id: String) {
        if (!vibrateOnPayment || !successNotifications.add(id)) return
        ignoreHapticFailure(haptics::notifyPaymentSuccess)
    }

    private suspend fun handlePendingPaymentLink() {
        if (activeLinkId != null || mutableUiState.value != PaymentUiState.Active) return
        when (val link = paymentLinks.claim() ?: return) {
            is ClaimedPaymentLink.Rejected -> {
                activeLinkId = link.id
                paymentLinks.consume(link.id)
                showError(PaymentUiError.InvalidInvoice("Unsupported payment link"))
            }

            is ClaimedPaymentLink.Request -> {
                activeLinkId = link.id
                val request = link.reveal()
                paymentLinks.consume(link.id)
                handlePaymentInput(request, PaymentOrigin.DEEP_LINK)
            }
        }
    }

    private fun resolveTargetPayment(
        address: LightningAddress,
        context: HubTargetContext,
        paymentQuote: PaymentAmountQuote?
    ) {
        scope.launch {
            actionMutex.withLock {
                handlePaymentInput(
                    rawInput = address.full,
                    origin = PaymentOrigin.DETECTED_CONTENT,
                    targetContext = context,
                    requestedAmountMsats = paymentQuote?.amountMsats,
                    paymentQuote = paymentQuote
                )
            }
        }
    }

    /** Maps a hub selection into Flint's own Spark preparation and confirmation flow. */
    private fun payTarget(intent: DirectTargetPaymentIntent) {
        val preset = (intent.amountRule as? DirectTargetAmountRule.Preset)?.amount
        val context =
            HubTargetContext(
                targetId = intent.targetId,
                address = intent.address,
                isPreset = preset != null
            )
        if (preset == null) {
            resolveTargetPayment(intent.address, context, null)
            return
        }
        scope.launch {
            val paymentQuote = currencyManager.quoteStoredAmount(preset)
            if (paymentQuote == null) {
                val info = CurrencyCatalog.infoFor(preset.normalizedCurrencyCode)
                showError(
                    if (info.currency is DisplayCurrency.Fiat) {
                        PaymentUiError.ExchangeRateUnavailable(info.code)
                    } else {
                        PaymentUiError.InvalidInvoice("Preset amount could not be converted")
                    }
                )
                return@launch
            }
            resolveTargetPayment(intent.address, context, paymentQuote)
        }
    }

    private suspend fun retryTransaction(id: String) {
        if (id !in sessionAttemptIds) return
        mutableUiState.value = PaymentUiState.Loading(LoadingKind.Resolving)
        engine.refresh()
        mutableUiState.value = PaymentUiState.Active
        requestTransactionDetailNavigation(id)
    }

    private fun sessionTransactionsOpened() {
        newSessionTransactionIds.clear()
        knownSessionTransactionIds += mutableSessionTransactions.value.map { it.id }
        mutableNewSessionTransactionCount.value = 0
    }

    private fun requestTransactionDetailNavigation(id: String) {
        if (mutableSessionTransactions.value.none { it.id == id }) return
        mutableUiState.value = PaymentUiState.Active
        mutableTransactionDetailNavigationTarget.value = id
    }

    private fun transactionDetailNavigationHandled(id: String) {
        if (mutableTransactionDetailNavigationTarget.value == id) {
            mutableTransactionDetailNavigationTarget.value = null
        }
    }

    private suspend fun dismissResult() {
        finishPaymentInteraction()
        handlePendingPaymentLink()
    }

    private fun finishPaymentInteraction() {
        pendingPresentationJob?.cancel()
        clearTransientPaymentState()
        activeLinkId?.let(paymentLinks::finish)
        activeLinkId = null
        mutableUiState.value = PaymentUiState.Active
    }

    private fun clearTransientPaymentState() {
        interaction.reset()
    }

    private fun showError(error: PaymentUiError, keepVisibleActivity: Boolean = false) {
        if (!keepVisibleActivity) interaction.clearVisibleActivity()
        mutableUiState.value = PaymentUiState.Error(error)
    }

    private fun refreshCurrencyDependentPresentation() {
        interaction.manualRequest?.let { request ->
            if (mutableUiState.value is PaymentUiState.EnterAmount) {
                showManualAmount(request, clearInput = false)
            }
        }
        interaction.visibleActivity?.let { visible ->
            when (mutableUiState.value) {
                is PaymentUiState.Success,
                is PaymentUiState.Error -> showActivity(
                    visible.activity,
                    visible.wasAlreadyPaid
                )

                else -> Unit
            }
        }
        refreshSessionTransactions(engine.activity.value)
    }

    private fun display(amount: Satoshi): DisplayAmount =
        currencyManager.convertMsatsToDisplay(amount.toMsats())

    private suspend fun confirmationAmount(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?
    ): PaymentConfirmationAmount {
        val exactSats = DisplayAmount(amountMsats / MSATS_PER_SAT, DisplayCurrency.Satoshi)
        paymentQuote?.let { quote ->
            return PaymentConfirmationAmount(
                primary = quote.requestedAmount,
                exactSats = exactSats.takeIf {
                    quote.requestedAmount.currency is DisplayCurrency.Fiat
                }
            )
        }
        val preferredAmount = currencyManager.convertMsatsToFreshDisplay(amountMsats)
        return PaymentConfirmationAmount(
            primary = preferredAmount,
            exactSats = exactSats.takeIf { preferredAmount.currency is DisplayCurrency.Fiat },
            primaryIsEstimate = preferredAmount.currency is DisplayCurrency.Fiat
        )
    }
}

private fun PaymentRejection.toPaymentUiError(): PaymentUiError = when (this) {
    PaymentRejection.UNSUPPORTED_INPUT,
    PaymentRejection.ON_CHAIN_NOT_ALLOWED,
    PaymentRejection.AMOUNT_REQUIRED,
    PaymentRejection.INVALID_AMOUNT,
    PaymentRejection.EXPIRED,
    PaymentRejection.WRONG_NETWORK,
    PaymentRejection.TOKEN_NOT_ALLOWED,
    PaymentRejection.SENDER_NOT_ALLOWED,
    PaymentRejection.METHOD_MISMATCH,
    PaymentRejection.CONVERSION_NOT_ALLOWED ->
        PaymentUiError.InvalidInvoice(toReadableMessage())

    PaymentRejection.INSUFFICIENT_FUNDS ->
        PaymentUiError.Spark(SparkPaymentError.Rejected("Insufficient balance"))
}

private fun CurrencyManagerError.toPaymentUiError(): PaymentUiError = when (this) {
    is CurrencyManagerError.ExchangeRateUnavailable ->
        PaymentUiError.ExchangeRateUnavailable(currencyCode)
}

private fun PaymentRejection.toReadableMessage(): String = name.lowercase().replace('_', ' ')

private fun PaymentPreferences.toFlintConfirmationPolicy(): PaymentConfirmationPolicy =
    PaymentConfirmationPolicy(
        mode =
            when (confirmationMode) {
                PaymentConfirmationMode.Always -> FlintConfirmationMode.ALWAYS
                PaymentConfirmationMode.Above -> FlintConfirmationMode.THRESHOLD
            },
        amountThresholdSats = Satoshi.positive(thresholdSats),
        feeThresholdSats = Satoshi.nonNegative(Long.MAX_VALUE)
    )

internal fun requiresPreparedPaymentConfirmation(
    engineRequiresConfirmation: Boolean,
    isPresetTarget: Boolean,
    amountEnteredManually: Boolean = false
): Boolean = !amountEnteredManually && (engineRequiresConfirmation || isPresetTarget)

private fun LnurlPayReviewDetails.toDisplay(): LnurlPayDisplay? = LnurlPayDisplay.fromUntrusted(
    domain = domain,
    description = description,
    imagePngBase64 = imagePngBase64,
    imageJpegBase64 = imageJpegBase64
)

private fun reusableLightningAddress(input: String): LightningAddress? =
    LightningAddress.parse(input)

private fun msatsToSatoshi(msats: Long): Satoshi? {
    if (msats <= 0) return null
    val sats = (msats + MSATS_PER_SAT - 1) / MSATS_PER_SAT
    return Satoshi.positive(sats)
}

private fun Satoshi.toMsats(): Long =
    value.coerceAtMost(Long.MAX_VALUE / MSATS_PER_SAT) * MSATS_PER_SAT

private inline fun ignoreHapticFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
        // Haptics are optional feedback.
    }
}

internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun roundToFullSatoshis(msats: Long): Long =
    ((msats + MSATS_PER_SAT - 1) / MSATS_PER_SAT) * MSATS_PER_SAT

private const val MSATS_PER_SAT = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val PENDING_PRESENTATION_TIMEOUT_MS = 4_000L
