package xyz.lilsus.lasr.feature.payment

import com.russhwolf.settings.Settings
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.lilsus.lasr.integration.nwc.NwcWallet
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentConfirmationPolicy
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent

class PaymentCoordinator(
    nwcWallet: NwcWallet,
    lnurlPayClient: LnurlPayClient,
    bitcoinPriceProvider: BitcoinPriceProvider,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    paymentHub: PaymentHubController,
    haptics: HapticFeedbackManager,
    showEstimatedFeeHint: Boolean = false,
    paymentAttemptSettings: Settings,
    coroutineContext: CoroutineContext = Dispatchers.Main
) {
    private val flow =
        LasrPaymentFlow(
            nwcWallet = nwcWallet,
            lnurlPayClient = lnurlPayClient,
            bitcoinPriceProvider = bitcoinPriceProvider,
            currencyPreferences = currencyPreferences,
            paymentPreferences = paymentPreferences,
            paymentHub = paymentHub,
            haptics = haptics,
            showEstimatedFeeHint = showEstimatedFeeHint,
            paymentAttemptSettings = paymentAttemptSettings,
            coroutineContext = coroutineContext
        )

    val uiState: StateFlow<PaymentUiState> = flow.uiState
    val events: SharedFlow<PaymentEvent> = flow.events
    val sessionTransactions: StateFlow<List<SessionTransactionItem>> =
        flow.sessionTransactions
    val transactionDetailNavigationTarget: StateFlow<String?> =
        flow.transactionDetailNavigationTarget
    val newSessionTransactionCount: StateFlow<Int> =
        flow.newSessionTransactionCount

    val isSubmitting: StateFlow<Boolean> get() = flow.isSubmitting

    fun dispatch(intent: PaymentIntent) {
        flow.dispatch(intent)
    }

    fun clear() {
        flow.clear()
    }

    fun resetSession() {
        flow.resetSession()
    }
}

private class LasrPaymentFlow(
    nwcWallet: NwcWallet,
    lnurlPayClient: LnurlPayClient,
    bitcoinPriceProvider: BitcoinPriceProvider,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    private val paymentHub: PaymentHubController,
    private val haptics: HapticFeedbackManager,
    showEstimatedFeeHint: Boolean,
    paymentAttemptSettings: Settings,
    coroutineContext: CoroutineContext
) {
    private val scope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val currencyManager = PaymentCurrencyManager(bitcoinPriceProvider, scope)
    private val presentation = PaymentPresentationPhase(currencyManager)
    private val pendingTracker =
        PendingPaymentTracker(
            lookupInvoice = nwcWallet::lookupInvoice,
            isInForeground = nwcWallet.isInForeground,
            currencyManager = currencyManager,
            scope = scope,
            showEstimatedFeeHint = showEstimatedFeeHint,
            store = PendingPaymentStore(paymentAttemptSettings)
        )
    private val sessionTasks = PaymentTaskRegistry(scope)
    private var runtimePreferences = PaymentRuntimePreferences()
    private val confirmation =
        PaymentConfirmationPhase(
            currencyManager = currencyManager,
            confirmationPolicy = PaymentConfirmationPolicy(paymentPreferences),
            presentation = presentation
        )
    private val admission =
        PaymentAdmissionPhase(
            lnurlPayClient = lnurlPayClient,
            currencyManager = currencyManager,
            pendingTracker = pendingTracker,
            presentation = presentation,
            confirmationIsIdle = { confirmation.isIdle },
            showLnurlPayDetails = { runtimePreferences.showLnurlPayDetails },
            notifyScanSuccess = ::notifyScanSuccess
        )
    private val reconciliation =
        PaymentReconciliationPhase(
            pendingTracker = pendingTracker,
            presentation = presentation,
            paymentHub = paymentHub,
            haptics = haptics,
            showEstimatedFeeHint = showEstimatedFeeHint,
            vibrateOnPayment = { runtimePreferences.vibrateOnPayment },
            offerToSaveNewTargets = { runtimePreferences.offerToSaveNewTargets }
        )
    private val execution =
        NwcPaymentExecutionPhase(
            nwcWallet = nwcWallet,
            scope = scope,
            pendingTracker = pendingTracker,
            presentation = presentation,
            onAttemptRegistered = reconciliation::registerAttempt
        )

    val uiState: StateFlow<PaymentUiState> = presentation.uiState
    val events: SharedFlow<PaymentEvent> = presentation.events
    val sessionTransactions: StateFlow<List<SessionTransactionItem>> =
        pendingTracker.displayItems
    val transactionDetailNavigationTarget: StateFlow<String?> =
        presentation.transactionDetailNavigationTarget
    val newSessionTransactionCount: StateFlow<Int> =
        presentation.newSessionTransactionCount

    init {
        scope.launch {
            paymentPreferences.preferences.collectLatest { preferences ->
                runtimePreferences =
                    PaymentRuntimePreferences(
                        vibrateOnScan = preferences.vibrateOnScan,
                        vibrateOnPayment = preferences.vibrateOnPayment,
                        showLnurlPayDetails = preferences.showLnurlPayDetails,
                        offerToSaveNewTargets = preferences.offerToSaveNewTargets
                    )
            }
        }
        scope.launch {
            paymentHub.paymentRequests.collect { intent ->
                launchSessionTask { token ->
                    handleAdmissionResult(admission.payTarget(intent, token), token)
                }
            }
        }
        scope.launch {
            currencyPreferences.code.collectLatest { code ->
                currencyManager.setPreferredCurrency(CurrencyCatalog.infoFor(code).currency)
            }
        }
        scope.launch {
            currencyManager.state.collectLatest {
                admission.refreshManualAmountState()
                presentation.refreshResult()
                pendingTracker.refreshDisplayItems()
            }
        }
        scope.launch {
            currencyManager.errors.collect { error ->
                // A background quote failure must not interrupt SAT entry or payment submission.
                presentation.emitErrorEvent(error.toPaymentUiError())
            }
        }
        scope.launch {
            pendingTracker.events.collect(::handlePendingEvent)
        }
        scope.launch {
            pendingTracker.displayItems.collect { items ->
                presentation.updateSessionTransactionIds(
                    items.mapTo(mutableSetOf(), SessionTransactionItem::id)
                )
            }
        }
        scope.launch {
            nwcWallet.sentPayments.collect(pendingTracker::applySentPayment)
        }
    }

    val isSubmitting: StateFlow<Boolean> get() = execution.isSubmitting

    fun dispatch(intent: PaymentIntent) {
        launchSessionTask { token ->
            handleIntent(intent, token)
        }
    }

    fun clear() {
        sessionTasks.reset()
        execution.reset()
        pendingTracker.close()
        scope.cancel()
    }

    fun resetSession() {
        sessionTasks.reset()
        execution.reset()
        admission.reset(currencyManager.state.value)
        confirmation.reset()
        reconciliation.reset()
        presentation.reset()
        pendingTracker.resetSession()
        paymentHub.resetSession()
    }

    private suspend fun handleIntent(intent: PaymentIntent, token: PaymentTaskToken) {
        token.ensureCurrent()
        when (intent) {
            PaymentIntent.DismissResult -> presentation.dismissResult()

            is PaymentIntent.TransactionDetailNavigationHandled ->
                presentation.onTransactionDetailNavigationHandled(intent.id)

            PaymentIntent.SessionTransactionsOpened ->
                reconciliation.sessionTransactionsOpened()

            is PaymentIntent.QrCodeScanned ->
                handleAdmissionResult(
                    admission.handlePaymentInput(
                        rawInput = intent.rawValue,
                        source = PaymentRequestSource.Camera,
                        token = token
                    ),
                    token
                )

            is PaymentIntent.DeepLinkReceived ->
                handleAdmissionResult(
                    admission.handlePaymentInput(
                        rawInput = intent.rawValue,
                        source = PaymentRequestSource.DeepLink,
                        token = token
                    ),
                    token
                )

            PaymentIntent.ManualAmountDismiss -> admission.dismissManualAmount()

            PaymentIntent.ManualAmountSubmit ->
                handleAdmissionResult(admission.submitManualAmount(token), token)

            is PaymentIntent.ManualAmountKeyPress ->
                admission.updateManualAmount(intent.key)

            is PaymentIntent.ManualAmountPreset ->
                admission.presetManualAmount(intent.amount)

            PaymentIntent.ConfirmPaymentDismiss ->
                handleConfirmationDismissal(confirmation.dismiss())

            PaymentIntent.ConfirmPaymentSubmit ->
                handleConfirmationResult(confirmation.submit(), token)

            PaymentIntent.PendingRetryCreateNewInvoice -> {
                val choice = reconciliation.takeNewInvoiceChoice()
                if (choice != null) {
                    handleAdmissionResult(
                        admission.continueDynamicPayment(
                            recordId = choice.recordId,
                            continuation = choice.continuation,
                            token = token
                        ),
                        token
                    )
                }
            }

            PaymentIntent.PendingRetryRetryPrevious ->
                reconciliation.takePendingRetryRecord()?.let(execution::retry)

            PaymentIntent.PendingRetryViewPending ->
                reconciliation.viewPendingPayment()

            PaymentIntent.PendingRetryDismiss ->
                reconciliation.dismissPendingRetry()

            is PaymentIntent.RetryTransaction ->
                reconciliation.retryRecord(intent.id)?.let(execution::retry)

            is PaymentIntent.RawInputSubmitted ->
                handleAdmissionResult(
                    admission.handlePaymentInput(
                        rawInput = intent.rawValue,
                        source = PaymentRequestSource.Camera,
                        token = token
                    ),
                    token
                )
        }
    }

    private suspend fun handleAdmissionResult(result: AdmissionResult, token: PaymentTaskToken) {
        token.ensureCurrent()
        when (result) {
            AdmissionResult.Presented -> Unit

            is AdmissionResult.Payment ->
                handleConfirmationResult(
                    confirmation.requestPayment(result.payment, token),
                    token
                )

            is AdmissionResult.LnurlReview ->
                handleConfirmationResult(
                    confirmation.reviewLnurlPayment(result.review, token),
                    token
                )

            is AdmissionResult.PendingClarification ->
                reconciliation.showPendingRetryPrompt(
                    record = result.record,
                    continuation = result.continuation
                )
        }
    }

    private suspend fun handleConfirmationResult(
        result: ConfirmationResult,
        token: PaymentTaskToken
    ) {
        token.ensureCurrent()
        when (result) {
            ConfirmationResult.Presented -> Unit

            is ConfirmationResult.Execute ->
                execution.start(result.payment)

            is ConfirmationResult.ResolveLnurl ->
                handleAdmissionResult(
                    admission.resolveApprovedLnurl(result.approval, token),
                    token
                )
        }
    }

    private fun handleConfirmationDismissal(dismissal: ConfirmationDismissal) {
        when (dismissal) {
            ConfirmationDismissal.None -> Unit

            ConfirmationDismissal.Active -> presentation.showActive()

            ConfirmationDismissal.ManualAmount -> admission.restoreManualAmount()

            ConfirmationDismissal.LnurlManualAmount ->
                admission.restoreLnurlManualAmount()
        }
    }

    private fun handlePendingEvent(event: PendingEvent) {
        admission.clearPaymentState(currencyManager.state.value)
        confirmation.reset()
        reconciliation.handlePendingEvent(event)
    }

    private fun launchSessionTask(block: suspend (PaymentTaskToken) -> Unit) {
        sessionTasks.launch { token ->
            try {
                block(token)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                token.ensureCurrent()
                presentation.presentError(cause.toPaymentUiError())
            }
        }
    }

    private fun notifyScanSuccess() {
        if (runtimePreferences.vibrateOnScan) haptics.notifyScanSuccess()
    }
}

private data class PaymentRuntimePreferences(
    val vibrateOnScan: Boolean = true,
    val vibrateOnPayment: Boolean = true,
    val showLnurlPayDetails: Boolean = false,
    val offerToSaveNewTargets: Boolean = true
)
