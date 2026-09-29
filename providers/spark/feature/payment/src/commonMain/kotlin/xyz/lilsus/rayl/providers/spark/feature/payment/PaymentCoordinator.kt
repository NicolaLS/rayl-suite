package xyz.lilsus.rayl.providers.spark.feature.payment

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import xyz.lilsus.rayl.providers.spark.application.payment.AmountRequiredPayment
import xyz.lilsus.rayl.providers.spark.application.payment.ClaimedPaymentLink
import xyz.lilsus.rayl.providers.spark.application.payment.ConfirmPaymentResult
import xyz.lilsus.rayl.providers.spark.application.payment.LnurlPayReviewDetails
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentConfirmationMode as SparkConfirmationMode
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentConfirmationPolicy
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentEngine
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentLinkInbox
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentOrigin
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentOutcome
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentReceipt
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentRejection
import xyz.lilsus.rayl.providers.spark.application.payment.PreparePaymentResult
import xyz.lilsus.rayl.providers.spark.application.payment.PreparedPayment
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.model.Satoshi
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
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
import xyz.lilsus.raylsuite.feature.paymentui.PaymentReceiptDetails
import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage
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

    private val mutableUiState = MutableStateFlow<PaymentUiState>(PaymentUiState.Active)
    val uiState: StateFlow<PaymentUiState> = mutableUiState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PaymentEvent> = mutableEvents.asSharedFlow()

    private val mutableSessionTransactions =
        MutableStateFlow<List<SessionTransactionItem>>(emptyList())
    val sessionTransactions: StateFlow<List<SessionTransactionItem>> =
        mutableSessionTransactions.asStateFlow()

    private val mutableNewSessionTransactionCount = MutableStateFlow(0)
    val newSessionTransactionCount: StateFlow<Int> =
        mutableNewSessionTransactionCount.asStateFlow()

    private val mutableTransactionDetailNavigationTarget = MutableStateFlow<String?>(null)
    val transactionDetailNavigationTarget: StateFlow<String?> =
        mutableTransactionDetailNavigationTarget.asStateFlow()

    private val receipts = mutableListOf<SessionReceipt>()
    private val knownSessionTransactionIds = mutableSetOf<String>()
    private val newSessionTransactionIds = mutableSetOf<String>()
    private val interaction = PaymentInteractionSession()
    private var activeLinkId: String? = null
    private var vibrateOnScan = true
    private var vibrateOnPayment = true
    private var offerToSaveNewTargets = true
    private val mutableIsSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = mutableIsSubmitting.asStateFlow()

    init {
        scope.launch {
            paymentPreferences.preferences.collectLatest { preferences ->
                vibrateOnScan = preferences.vibrateOnScan
                vibrateOnPayment = preferences.vibrateOnPayment
                offerToSaveNewTargets = preferences.offerToSaveNewTargets
                engine.updateConfirmationPolicy(preferences.toSparkConfirmationPolicy())
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
            paymentHub.paymentRequests.collect(::payTarget)
        }
        scope.launch {
            paymentLinks.revision.collect {
                if (mutableIsSubmitting.value || mutableUiState.value != PaymentUiState.Active) {
                    paymentLinks.clear()
                } else {
                    val link = paymentLinks.claim() ?: return@collect
                    launchAction { handlePaymentLink(link) }
                }
            }
        }
    }

    fun dispatch(intent: PaymentIntent) {
        if (!scope.isActive) return
        if (intent is PaymentIntent.TransactionDetailNavigationHandled) {
            transactionDetailNavigationHandled(intent.id)
            return
        }
        if (mutableIsSubmitting.value) return
        when (intent) {
            PaymentIntent.SessionTransactionsOpened -> sessionTransactionsOpened()

            is PaymentIntent.TransactionDetailNavigationHandled ->
                transactionDetailNavigationHandled(intent.id)

            is PaymentIntent.ManualAmountKeyPress -> updateManualAmount(intent.key)

            PaymentIntent.DismissResult -> dismissResult()

            else -> launchAction { handleIntent(intent) }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (!scope.isActive || !actionMutex.tryLock()) return
        mutableIsSubmitting.value = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                block()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                showError(PaymentUiError.Unexpected())
            } finally {
                mutableIsSubmitting.value = false
                actionMutex.unlock()
            }
        }
    }

    fun clear() {
        scope.cancel()
        paymentLinks.clear()
    }

    fun resetSession() {
        if (mutableIsSubmitting.value) return
        paymentLinks.clear()
        activeLinkId = null
        receipts.clear()
        knownSessionTransactionIds.clear()
        newSessionTransactionIds.clear()
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
                    origin = PaymentOrigin.DETECTED_CONTENT
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
        }
    }

    private suspend fun handlePaymentInput(
        rawInput: String,
        origin: PaymentOrigin,
        targetContext: HubTargetContext? = reusableLightningAddress(rawInput)?.let {
            HubTargetContext(targetId = null, address = it, isPreset = false)
        },
        requestedAmountMsats: Long? = null,
        paymentQuote: PaymentAmountQuote? = null
    ) {
        if (mutableUiState.value != PaymentUiState.Active) return
        clearTransientPaymentState()
        mutableUiState.value = PaymentUiState.Loading(LoadingKind.Resolving)
        val result = engine.prepare(rawInput, origin)
        notifyAcceptedScan(result)
        applyPrepareResult(
            result = result,
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
        currentCoroutineContext().ensureActive()
        when (result) {
            is PreparePaymentResult.AmountRequired -> {
                val lnurlPayDisplay =
                    result.payment.lnurlPayDetails?.toDisplay(targetContext?.address) ?: run {
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
                recordReceipt(
                    result.receipt,
                    recipient = targetContext?.address?.full,
                    domain = targetContext?.address?.domain
                )
                requestTransactionDetailNavigation(result.receipt.id)
            }

            is PreparePaymentResult.InvalidInput -> handleInvalidInput(result.reason)

            is PreparePaymentResult.Rejected -> when (result.reason) {
                PaymentRejection.ON_CHAIN_NOT_ALLOWED ->
                    showUnsupportedInput(PaymentToastMessage.BitcoinAddressNotSupported)

                PaymentRejection.UNSUPPORTED_INPUT ->
                    showUnsupportedInput(PaymentToastMessage.PaymentLinkNotSupported)

                else -> showError(result.reason.toPaymentUiError())
            }

            PreparePaymentResult.WalletUnavailable ->
                showError(PaymentUiError.Spark(SparkPaymentError.WalletUnavailable))

            PreparePaymentResult.SdkFailure ->
                showError(PaymentUiError.Spark(SparkPaymentError.SdkUnavailable))
        }
    }

    private fun notifyAcceptedScan(result: PreparePaymentResult) {
        if (vibrateOnScan &&
            (result is PreparePaymentResult.AmountRequired || result is PreparePaymentResult.Ready)
        ) {
            ignoreHapticFailure(haptics::notifyScanSuccess)
        }
    }

    private fun handleInvalidInput(reason: LightningInputParser.FailureReason) {
        when (reason) {
            LightningInputParser.FailureReason.Empty,
            LightningInputParser.FailureReason.Unrecognized -> finishPaymentInteraction()

            LightningInputParser.FailureReason.BitcoinAddress ->
                showUnsupportedInput(PaymentToastMessage.BitcoinAddressNotSupported)

            LightningInputParser.FailureReason.Bolt12 ->
                showUnsupportedInput(PaymentToastMessage.Bolt12NotSupported)

            LightningInputParser.FailureReason.UnsupportedLnurl ->
                showUnsupportedInput(PaymentToastMessage.LnurlRequestNotSupported)

            LightningInputParser.FailureReason.UnsupportedDeepLink ->
                showUnsupportedInput(PaymentToastMessage.PaymentLinkNotSupported)

            LightningInputParser.FailureReason.InvalidLnurl ->
                showError(PaymentUiError.InvalidInvoice("Invalid LNURL request"))

            is LightningInputParser.FailureReason.InvalidInvoice ->
                showError(PaymentUiError.InvalidInvoice(reason.reason))
        }
    }

    private fun showUnsupportedInput(message: PaymentToastMessage) {
        finishPaymentInteraction()
        mutableEvents.tryEmit(PaymentEvent.ShowToast(message))
    }

    private suspend fun handlePreparedPayment(
        payment: PreparedPayment,
        targetContext: HubTargetContext?,
        paymentQuote: PaymentAmountQuote?,
        amountEnteredManually: Boolean
    ) {
        interaction.reset()
        val lnurlPayDisplay = payment.lnurlPayDetails?.toDisplay(targetContext?.address)
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
        if (mutableUiState.value !is PaymentUiState.Confirm) return
        val draft = interaction.activeDraft ?: return
        mutableUiState.value = PaymentUiState.Loading()
        applyConfirmResult(engine.confirm(draft.payment.handle), draft)
    }

    private suspend fun applyConfirmResult(result: ConfirmPaymentResult, draft: ActiveDraft) {
        currentCoroutineContext().ensureActive()
        when (result) {
            is ConfirmPaymentResult.Completed -> {
                recordReceipt(
                    result.receipt,
                    recipient = draft.targetContext?.address?.full,
                    domain = draft.lnurlPayDisplay?.domain
                )
                if (result.receipt.outcome == PaymentOutcome.COMPLETED && !result.wasAlreadyPaid) {
                    if (vibrateOnPayment) ignoreHapticFailure(haptics::notifyPaymentSuccess)
                    draft.targetContext?.let { context ->
                        if (context.targetId != null) {
                            paymentHub.recordSuccessfulPayment(
                                context.targetId,
                                result.receipt.amountSats.value
                            )
                        } else if (offerToSaveNewTargets) {
                            paymentHub.offerSave(context.address)
                        }
                    }
                }
                if (result.wasAlreadyPaid) {
                    requestTransactionDetailNavigation(result.receipt.id)
                } else {
                    showReceipt(result.receipt)
                }
            }

            ConfirmPaymentResult.OutcomeUnknown ->
                showError(PaymentUiError.Spark(SparkPaymentError.OutcomeUnknown))

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
        }
    }

    private suspend fun dismissManualAmount() {
        if (mutableUiState.value !is PaymentUiState.EnterAmount) return
        val request = interaction.manualRequest ?: return
        engine.cancel(request.payment.handle)
        currentCoroutineContext().ensureActive()
        finishPaymentInteraction()
    }

    private suspend fun dismissConfirmation() {
        if (mutableUiState.value !is PaymentUiState.Confirm) return
        val draft = interaction.activeDraft ?: return
        engine.cancel(draft.payment.handle)
        currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()
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

    private fun showReceipt(receipt: PaymentReceipt, wasAlreadyPaid: Boolean = false) {
        interaction.showResult(VisibleReceipt(receipt, wasAlreadyPaid))
        mutableUiState.value = when (receipt.outcome) {
            PaymentOutcome.COMPLETED -> PaymentUiState.Success(
                amountPaid = display(receipt.amountSats),
                feePaid = display(receipt.feeSats),
                wasAlreadyPaid = wasAlreadyPaid
            )

            PaymentOutcome.FAILED -> PaymentUiState.Error(
                PaymentUiError.Spark(SparkPaymentError.Rejected("The Spark payment failed"))
            )
        }
    }

    private fun recordReceipt(
        receipt: PaymentReceipt,
        recipient: String? = null,
        domain: String? = null
    ) {
        if (receipts.none { it.payment.id == receipt.id }) {
            receipts.add(0, SessionReceipt(receipt, recipient, domain))
        }
        while (receipts.size > MAX_SESSION_RECEIPTS) receipts.removeAt(receipts.lastIndex)
        refreshSessionTransactions()
    }

    private fun refreshSessionTransactions() {
        val items = receipts.map { it.toSessionTransactionItem() }
        mutableSessionTransactions.value = items
        val ids = items.mapTo(mutableSetOf()) { it.id }
        knownSessionTransactionIds.retainAll(ids)
        newSessionTransactionIds.retainAll(ids)
        val unseen = items.map { it.id }.filterNot(knownSessionTransactionIds::contains)
        if (unseen.isNotEmpty()) {
            knownSessionTransactionIds += unseen
            newSessionTransactionIds += unseen
        }
        mutableNewSessionTransactionCount.value = newSessionTransactionIds.size
    }

    private fun SessionReceipt.toSessionTransactionItem(): SessionTransactionItem = with(payment) {
        SessionTransactionItem(
            id = id,
            amount = display(amountSats),
            status = when (outcome) {
                PaymentOutcome.COMPLETED -> TransactionStatus.Succeeded
                PaymentOutcome.FAILED -> TransactionStatus.Failed
            },
            createdAtMs = createdAtEpochSeconds * MILLIS_PER_SECOND,
            resultAmount = display(amountSats),
            fee = display(feeSats),
            error = if (outcome == PaymentOutcome.FAILED) {
                PaymentUiError.Spark(SparkPaymentError.Rejected("The Spark payment failed"))
            } else {
                null
            },
            receipt = if (outcome == PaymentOutcome.COMPLETED) {
                PaymentReceiptDetails(
                    amount = display(amountSats),
                    fee = display(feeSats),
                    amountSats = DisplayAmount(amountSats.value, DisplayCurrency.Satoshi),
                    feeSats = DisplayAmount(feeSats.value, DisplayCurrency.Satoshi),
                    createdAtMs = createdAtEpochSeconds * MILLIS_PER_SECOND,
                    recipient = recipient,
                    domain = domain,
                    preimage = preimage
                )
            } else {
                null
            }
        )
    }

    private suspend fun handlePaymentLink(link: ClaimedPaymentLink) {
        when (link) {
            is ClaimedPaymentLink.Rejected -> {
                activeLinkId = link.id
                paymentLinks.consume(link.id)
                showUnsupportedInput(PaymentToastMessage.PaymentLinkNotSupported)
            }

            is ClaimedPaymentLink.Request -> {
                activeLinkId = link.id
                val request = link.reveal()
                paymentLinks.consume(link.id)
                handlePaymentInput(request, PaymentOrigin.DEEP_LINK)
            }
        }
    }

    /** Maps a hub selection into Spark's own Spark preparation and confirmation flow. */
    private fun payTarget(intent: DirectTargetPaymentIntent) {
        if (mutableUiState.value != PaymentUiState.Active) return
        launchAction {
            clearTransientPaymentState()
            mutableUiState.value = PaymentUiState.Loading(LoadingKind.Resolving)
            val preset = (intent.amountRule as? DirectTargetAmountRule.Preset)?.amount
            val context = HubTargetContext(
                targetId = intent.targetId,
                address = intent.address,
                isPreset = preset != null
            )
            val quote = preset?.let { currencyManager.quoteStoredAmount(it) }
            currentCoroutineContext().ensureActive()
            if (preset != null && quote == null) {
                val info = CurrencyCatalog.infoFor(preset.normalizedCurrencyCode)
                showError(
                    if (info.currency is DisplayCurrency.Fiat) {
                        PaymentUiError.ExchangeRateUnavailable(info.code)
                    } else {
                        PaymentUiError.InvalidInvoice("Preset amount could not be converted")
                    }
                )
                return@launchAction
            }
            val result = engine.prepare(
                intent.address.full,
                PaymentOrigin.DETECTED_CONTENT,
                comment = intent.comment
            )
            notifyAcceptedScan(result)
            applyPrepareResult(
                result = result,
                targetContext = context,
                requestedAmountMsats = quote?.amountMsats,
                paymentQuote = quote
            )
        }
    }

    private fun sessionTransactionsOpened() {
        newSessionTransactionIds.clear()
        knownSessionTransactionIds += mutableSessionTransactions.value.map { it.id }
        mutableNewSessionTransactionCount.value = 0
    }

    private fun requestTransactionDetailNavigation(id: String) {
        knownSessionTransactionIds += id
        newSessionTransactionIds -= id
        mutableNewSessionTransactionCount.value = newSessionTransactionIds.size
        finishPaymentInteraction()
        mutableTransactionDetailNavigationTarget.value = id
    }

    private fun transactionDetailNavigationHandled(id: String) {
        if (mutableTransactionDetailNavigationTarget.value == id) {
            mutableTransactionDetailNavigationTarget.value = null
        }
    }

    private fun dismissResult() {
        if (mutableUiState.value is PaymentUiState.Success ||
            mutableUiState.value is PaymentUiState.Error
        ) {
            finishPaymentInteraction()
        }
    }

    private fun finishPaymentInteraction() {
        clearTransientPaymentState()
        activeLinkId?.let(paymentLinks::finish)
        activeLinkId = null
        mutableUiState.value = PaymentUiState.Active
    }

    private fun clearTransientPaymentState() {
        interaction.reset()
    }

    private fun showError(error: PaymentUiError) {
        interaction.reset()
        mutableUiState.value = PaymentUiState.Error(error)
    }

    private fun refreshCurrencyDependentPresentation() {
        interaction.manualRequest?.let { request ->
            if (mutableUiState.value is PaymentUiState.EnterAmount) {
                showManualAmount(request, clearInput = false)
            }
        }
        interaction.visibleReceipt?.let { visible ->
            when (mutableUiState.value) {
                is PaymentUiState.Success,
                is PaymentUiState.Error -> showReceipt(
                    visible.receipt,
                    visible.wasAlreadyPaid
                )

                else -> Unit
            }
        }
        refreshSessionTransactions()
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
        currentCoroutineContext().ensureActive()
        return PaymentConfirmationAmount(
            primary = preferredAmount,
            exactSats = exactSats.takeIf { preferredAmount.currency is DisplayCurrency.Fiat },
            primaryIsEstimate = preferredAmount.currency is DisplayCurrency.Fiat
        )
    }
}

private data class SessionReceipt(
    val payment: PaymentReceipt,
    val recipient: String?,
    val domain: String?
)

private fun PaymentRejection.toPaymentUiError(): PaymentUiError = when (this) {
    PaymentRejection.COMMENT_NOT_ALLOWED ->
        PaymentUiError.InvalidInvoice("Description is too long for this address")

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

private fun PaymentPreferences.toSparkConfirmationPolicy(): PaymentConfirmationPolicy =
    PaymentConfirmationPolicy(
        mode =
            when (confirmationMode) {
                PaymentConfirmationMode.Always -> SparkConfirmationMode.ALWAYS
                PaymentConfirmationMode.Above -> SparkConfirmationMode.THRESHOLD
            },
        amountThresholdSats = Satoshi.positive(thresholdSats),
        feeThresholdSats = Satoshi.nonNegative(Long.MAX_VALUE)
    )

internal fun requiresPreparedPaymentConfirmation(
    engineRequiresConfirmation: Boolean,
    isPresetTarget: Boolean,
    amountEnteredManually: Boolean = false
): Boolean = !amountEnteredManually && (engineRequiresConfirmation || isPresetTarget)

private fun LnurlPayReviewDetails.toDisplay(address: LightningAddress?): LnurlPayDisplay? =
    LnurlPayDisplay.fromUntrusted(
        domain = domain,
        description = description,
        imagePngBase64 = imagePngBase64,
        imageJpegBase64 = imageJpegBase64,
        lightningAddress = address
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

private const val MSATS_PER_SAT = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_SESSION_RECEIPTS = 10
