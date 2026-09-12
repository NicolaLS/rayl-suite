package xyz.lilsus.lasr.feature.payment

import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.currentTimestampSeconds
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.payment.DynamicPaymentSourceKey
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceResolution
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.core.payment.LnurlResult
import xyz.lilsus.raylsuite.core.payment.lightningAddressDynamicPaymentSourceKey
import xyz.lilsus.raylsuite.core.payment.lnurlDynamicPaymentSourceKey
import xyz.lilsus.raylsuite.feature.paymentcurrency.CurrencyState
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule
import xyz.lilsus.raylsuite.feature.paymenthub.host.DirectTargetPaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountConfig
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

internal class PaymentAdmissionPhase(
    private val lnurlPayClient: LnurlPayClient,
    private val currencyManager: PaymentCurrencyManager,
    private val pendingTracker: PendingPaymentTracker,
    private val presentation: PaymentPresentationPhase,
    private val confirmationIsIdle: () -> Boolean,
    private val showLnurlPayDetails: () -> Boolean,
    private val notifyScanSuccess: () -> Unit
) {
    private val preparation = PaymentPreparation(lnurlPayClient)
    private val admissionSession = PaymentAdmissionSession()
    private val inputParser = preparation.inputParser
    private val manualAmount = preparation.manualAmount

    suspend fun handlePaymentInput(
        rawInput: String,
        source: PaymentRequestSource,
        token: PaymentTaskToken
    ): AdmissionResult {
        if (
            presentation.uiState.value != PaymentUiState.Active ||
            !confirmationIsIdle() ||
            !admissionSession.begin(token)
        ) {
            return AdmissionResult.Presented
        }
        preparation.manualEntryContext = null

        return try {
            val parseResult =
                if (source == PaymentRequestSource.DeepLink) {
                    inputParser.parseDeepLink(rawInput)
                } else {
                    inputParser.parse(rawInput)
                }
            when (val result = parseResult) {
                is LightningInputParser.ParseResult.Failure -> {
                    handleParseFailure(result.reason)
                    AdmissionResult.Presented
                }

                is LightningInputParser.ParseResult.Success ->
                    when (val target = result.target) {
                        is LightningInputParser.Target.Bolt11 -> {
                            pendingTracker.findLatestByPaymentHash(
                                target.invoice.paymentHash.toHex()
                            )
                                ?.let { existing ->
                                    requestTransactionDetailNavigation(existing.id)
                                    return AdmissionResult.Presented
                                }
                            if (rejectExpiredInvoice(target.invoice)) {
                                return AdmissionResult.Presented
                            }
                            notifyScanSuccess()
                            processBoltInvoice(target.invoice, source)
                        }

                        is LightningInputParser.Target.Lnurl -> {
                            val sourceKey = lnurlDynamicPaymentSourceKey(target.endpoint)
                            val existing =
                                pendingTracker.findGuardingByDynamicSourceKey(sourceKey)
                            if (existing != null) {
                                AdmissionResult.PendingClarification(
                                    record = existing,
                                    continuation =
                                        PendingRetryContinuation.Lnurl(
                                            endpoint = target.endpoint,
                                            sourceKey = sourceKey,
                                            paymentSource = source
                                        )
                                )
                            } else {
                                notifyScanSuccess()
                                fetchLnurl(
                                    endpoint = target.endpoint,
                                    paymentSource = source,
                                    sourceKey = sourceKey,
                                    token = token
                                )
                            }
                        }

                        is LightningInputParser.Target.LightningAddressTarget -> {
                            val sourceKey =
                                lightningAddressDynamicPaymentSourceKey(target.address)
                            val targetContext =
                                HubTargetContext(
                                    targetId = null,
                                    address = target.address,
                                    isPreset = false
                                )
                            val existing =
                                pendingTracker.findGuardingByDynamicSourceKey(sourceKey)
                            if (existing != null) {
                                AdmissionResult.PendingClarification(
                                    record = existing,
                                    continuation =
                                        PendingRetryContinuation.LightningAddress(
                                            address = target.address,
                                            sourceKey = sourceKey,
                                            paymentSource = source,
                                            targetContext = targetContext
                                        )
                                )
                            } else {
                                notifyScanSuccess()
                                resolveLightningAddress(
                                    address = target.address,
                                    paymentSource = source,
                                    sourceKey = sourceKey,
                                    targetContext = targetContext,
                                    token = token
                                )
                            }
                        }

                        else -> AdmissionResult.Presented
                    }
            }
        } finally {
            admissionSession.complete(token)
        }
    }

    suspend fun payTarget(
        intent: DirectTargetPaymentIntent,
        token: PaymentTaskToken
    ): AdmissionResult {
        if (
            presentation.uiState.value != PaymentUiState.Active ||
            !confirmationIsIdle() ||
            !admissionSession.begin(token)
        ) {
            return AdmissionResult.Presented
        }
        return try {
            val preset = (intent.amountRule as? DirectTargetAmountRule.Preset)?.amount
            val context =
                HubTargetContext(
                    targetId = intent.targetId,
                    address = intent.address,
                    isPreset = preset != null
                )
            if (preset == null) {
                resolveTargetPayment(
                    address = intent.address,
                    context = context,
                    paymentQuote = null,
                    comment = intent.comment,
                    token = token
                )
            } else {
                val paymentQuote = currencyManager.quoteStoredAmount(preset)
                token.ensureCurrent()
                if (paymentQuote == null) {
                    val info = CurrencyCatalog.infoFor(preset.normalizedCurrencyCode)
                    presentation.presentError(
                        if (info.currency is DisplayCurrency.Fiat) {
                            PaymentUiError.ExchangeRateUnavailable(info.code)
                        } else {
                            PaymentUiError.InvalidInvoice(
                                "Preset amount could not be converted"
                            )
                        }
                    )
                    AdmissionResult.Presented
                } else {
                    resolveTargetPayment(
                        address = intent.address,
                        context = context,
                        paymentQuote = paymentQuote,
                        comment = intent.comment,
                        token = token
                    )
                }
            }
        } finally {
            admissionSession.complete(token)
        }
    }

    suspend fun submitManualAmount(token: PaymentTaskToken): AdmissionResult {
        val entryState = presentation.uiState.value as? PaymentUiState.EnterAmount
            ?: return AdmissionResult.Presented
        val context = preparation.manualEntryContext ?: return AdmissionResult.Presented
        val enteredAmount = manualAmount.enteredAmount() ?: return AdmissionResult.Presented
        presentation.showLoading(LoadingKind.Resolving)
        val paymentQuote = currencyManager.quote(enteredAmount)
        token.ensureCurrent()
        if (paymentQuote == null) {
            presentation.showManualAmount(entryState.entry, entryState.lnurlPayDisplay)
            val error =
                when (val currency = enteredAmount.currency) {
                    is DisplayCurrency.Fiat ->
                        PaymentUiError.ExchangeRateUnavailable(currency.iso4217)

                    else -> PaymentUiError.InvalidInvoice("Amount could not be converted")
                }
            presentation.emitErrorEvent(error)
            return AdmissionResult.Presented
        }

        return when (context) {
            is ManualEntryContext.Bolt ->
                AdmissionResult.Payment(
                    PreparedPayment(
                        invoice = context.invoice,
                        amountOverrideMsats = paymentQuote.amountMsats,
                        origin = PendingOrigin.ManualEntry,
                        source = context.source,
                        paymentQuote = paymentQuote
                    )
                )

            is ManualEntryContext.Lnurl -> {
                val roundedAmount = paymentQuote.amountMsats
                if (
                    roundedAmount < context.session.params.minSendable ||
                    roundedAmount > context.session.params.maxSendable
                ) {
                    presentation.showManualAmount(entryState.entry, entryState.lnurlPayDisplay)
                    presentation.emitErrorEvent(
                        PaymentUiError.InvalidInvoice(
                            "Amount is outside the allowed range"
                        )
                    )
                    AdmissionResult.Presented
                } else {
                    resolveLnurlInvoice(
                        session = context.session,
                        amountMsats = roundedAmount,
                        isManualEntry = true,
                        paymentQuote = paymentQuote,
                        token = token
                    )
                }
            }
        }
    }

    suspend fun resolveApprovedLnurl(
        approval: ApprovedLnurlReview,
        token: PaymentTaskToken
    ): AdmissionResult = resolveLnurlInvoice(
        session = approval.request.session,
        amountMsats = approval.request.amountMsats,
        isManualEntry = approval.request.isManualEntry,
        paymentQuote = approval.request.paymentQuote,
        token = token
    )

    suspend fun continueDynamicPayment(
        recordId: String,
        continuation: PendingRetryContinuation,
        token: PaymentTaskToken
    ): AdmissionResult {
        notifyScanSuccess()
        return when (continuation) {
            is PendingRetryContinuation.Lnurl ->
                fetchLnurl(
                    endpoint = continuation.endpoint,
                    paymentSource = continuation.paymentSource,
                    sourceKey = continuation.sourceKey,
                    replacesDynamicGuardId = recordId,
                    token = token
                )

            is PendingRetryContinuation.LightningAddress ->
                resolveLightningAddress(
                    address = continuation.address,
                    paymentSource = continuation.paymentSource,
                    sourceKey = continuation.sourceKey,
                    targetContext = continuation.targetContext,
                    presetQuote = continuation.presetQuote,
                    targetComment = continuation.targetComment,
                    replacesDynamicGuardId = recordId,
                    token = token
                )
        }
    }

    fun updateManualAmount(key: ManualAmountKey) {
        val state = presentation.uiState.value as? PaymentUiState.EnterAmount ?: return
        preparation.manualEntryContext ?: return
        presentation.showManualAmount(
            manualAmount.handleKeyPress(key),
            state.lnurlPayDisplay
        )
    }

    fun dismissManualAmount() {
        manualAmount.reset()
        preparation.manualEntryContext = null
        presentation.showActive()
    }

    fun restoreManualAmount() {
        presentation.showManualAmount(manualAmount.current())
    }

    fun restoreLnurlManualAmount() {
        val display =
            (preparation.manualEntryContext as? ManualEntryContext.Lnurl)
                ?.session
                ?.display
        presentation.showManualAmount(manualAmount.current(), display)
    }

    fun refreshManualAmountState() {
        val preserveInput = preparation.manualEntryContext != null
        val currencyState = currencyManager.state.value
        val manualInfo =
            when (val context = preparation.manualEntryContext) {
                is ManualEntryContext.Lnurl -> context.inputInfo
                else -> currencyState.info
            }
        val manualCurrencyState =
            CurrencyState(
                info = manualInfo,
                exchangeRate =
                    currencyState.exchangeRate.takeIf {
                        manualInfo.code.equals(currencyState.info.code, ignoreCase = true)
                    }
            )
        val config =
            when (val context = preparation.manualEntryContext) {
                is ManualEntryContext.Lnurl ->
                    ManualAmountConfig(
                        info = manualCurrencyState.info,
                        exchangeRate = manualCurrencyState.exchangeRate,
                        minMsats = context.session.params.minSendable,
                        maxMsats = context.session.params.maxSendable
                    )

                else ->
                    ManualAmountConfig(
                        info = manualCurrencyState.info,
                        exchangeRate = manualCurrencyState.exchangeRate
                    )
            }
        val entry = manualAmount.reset(config, clearInput = !preserveInput)
        if (presentation.uiState.value is PaymentUiState.EnterAmount) {
            val display =
                (preparation.manualEntryContext as? ManualEntryContext.Lnurl)
                    ?.session
                    ?.display
            presentation.showManualAmount(entry, display)
        }
    }

    fun clearPaymentState(currencyState: CurrencyState) {
        preparation.reset(currencyState)
    }

    fun reset(currencyState: CurrencyState) {
        admissionSession.reset()
        preparation.reset(currencyState)
    }

    private fun handleParseFailure(reason: LightningInputParser.FailureReason) {
        when (reason) {
            LightningInputParser.FailureReason.BitcoinAddress ->
                presentation.showToast(PaymentToastMessage.BitcoinAddressNotSupported)

            LightningInputParser.FailureReason.Bolt12 ->
                presentation.showToast(PaymentToastMessage.Bolt12NotSupported)

            LightningInputParser.FailureReason.UnsupportedLnurl ->
                presentation.showToast(PaymentToastMessage.LnurlRequestNotSupported)

            LightningInputParser.FailureReason.InvalidLnurl ->
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("Invalid LNURL request")
                )

            LightningInputParser.FailureReason.UnsupportedDeepLink ->
                presentation.showToast(PaymentToastMessage.PaymentLinkNotSupported)

            is LightningInputParser.FailureReason.InvalidInvoice ->
                presentation.presentError(PaymentUiError.InvalidInvoice(reason.reason))

            LightningInputParser.FailureReason.Empty,
            LightningInputParser.FailureReason.Unrecognized -> Unit
        }
    }

    private fun processBoltInvoice(
        invoice: Bolt11Invoice,
        source: PaymentRequestSource
    ): AdmissionResult {
        val entry =
            manualAmount.reset(
                ManualAmountConfig(
                    info = currencyManager.state.value.info,
                    exchangeRate = currencyManager.state.value.exchangeRate
                ),
                clearInput = true
            )
        return if (invoice.amount == null) {
            preparation.manualEntryContext = ManualEntryContext.Bolt(invoice, source)
            presentation.showManualAmount(entry)
            AdmissionResult.Presented
        } else {
            AdmissionResult.Payment(
                PreparedPayment(
                    invoice = invoice,
                    amountOverrideMsats = null,
                    origin = PendingOrigin.Invoice,
                    source = source
                )
            )
        }
    }

    private suspend fun fetchLnurl(
        endpoint: String,
        paymentSource: PaymentRequestSource,
        sourceKey: DynamicPaymentSourceKey?,
        token: PaymentTaskToken,
        replacesDynamicGuardId: String? = null
    ): AdmissionResult {
        presentation.showLoading(LoadingKind.Resolving)
        val result = lnurlPayClient.fetchPayParams(endpoint)
        token.ensureCurrent()
        return when (result) {
            is LnurlResult.Success ->
                handleLnurlParams(
                    params = result.data,
                    paymentSource = paymentSource,
                    sourceKey = sourceKey,
                    replacesDynamicGuardId = replacesDynamicGuardId,
                    token = token
                )

            is LnurlResult.Error -> {
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private suspend fun resolveLightningAddress(
        address: LightningAddress,
        paymentSource: PaymentRequestSource,
        sourceKey: DynamicPaymentSourceKey?,
        token: PaymentTaskToken,
        targetContext: HubTargetContext? = null,
        presetQuote: PaymentAmountQuote? = null,
        targetComment: String? = null,
        replacesDynamicGuardId: String? = null
    ): AdmissionResult {
        presentation.showLoading(LoadingKind.Resolving)
        val result = lnurlPayClient.fetchPayParams(address)
        token.ensureCurrent()
        return when (result) {
            is LnurlResult.Success ->
                handleLnurlParams(
                    params = result.data,
                    paymentSource = paymentSource,
                    sourceKey = sourceKey,
                    targetContext = targetContext,
                    presetQuote = presetQuote,
                    targetComment = targetComment,
                    replacesDynamicGuardId = replacesDynamicGuardId,
                    token = token
                )

            is LnurlResult.Error -> {
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private suspend fun resolveTargetPayment(
        address: LightningAddress,
        context: HubTargetContext,
        paymentQuote: PaymentAmountQuote?,
        comment: String?,
        token: PaymentTaskToken
    ): AdmissionResult {
        val sourceKey = lightningAddressDynamicPaymentSourceKey(address)
        val existing = pendingTracker.findGuardingByDynamicSourceKey(sourceKey)
        if (existing != null) {
            return AdmissionResult.PendingClarification(
                record = existing,
                continuation =
                    PendingRetryContinuation.LightningAddress(
                        address = address,
                        sourceKey = sourceKey,
                        paymentSource = PaymentRequestSource.Camera,
                        targetContext = context,
                        presetQuote = paymentQuote,
                        targetComment = comment
                    )
            )
        }
        notifyScanSuccess()
        return resolveLightningAddress(
            address = address,
            paymentSource = PaymentRequestSource.Camera,
            sourceKey = sourceKey,
            targetContext = context,
            presetQuote = paymentQuote,
            targetComment = comment,
            token = token
        )
    }

    private suspend fun handleLnurlParams(
        params: LnurlPayParams,
        paymentSource: PaymentRequestSource,
        sourceKey: DynamicPaymentSourceKey? = null,
        targetContext: HubTargetContext? = null,
        presetQuote: PaymentAmountQuote? = null,
        targetComment: String? = null,
        replacesDynamicGuardId: String? = null,
        token: PaymentTaskToken
    ): AdmissionResult {
        if (params.minSendable <= 0 || params.maxSendable < params.minSendable) {
            presentation.presentError(
                PaymentUiError.InvalidInvoice("LNURL amount range is invalid")
            )
            return AdmissionResult.Presented
        }
        val lnurlPayDisplay =
            LnurlPayDisplay.fromUntrusted(
                domain = params.domain,
                description = params.metadata.plainText,
                imagePngBase64 = params.metadata.imagePng,
                imageJpegBase64 = params.metadata.imageJpeg
            ) ?: run {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("LNURL payment details are invalid")
                )
                return AdmissionResult.Presented
            }
        val session =
            LnurlSession(
                params = params,
                display = lnurlPayDisplay,
                requiresDetailsReview = showLnurlPayDetails(),
                sourceKey = sourceKey,
                paymentSource = paymentSource,
                targetContext = targetContext,
                comment = targetComment,
                replacesDynamicGuardId = replacesDynamicGuardId
            )
        val currencyState = currencyManager.state.value
        val inputInfo = currencyState.info
        val manualCurrencyState =
            CurrencyState(
                info = inputInfo,
                exchangeRate =
                    currencyState.exchangeRate.takeIf {
                        inputInfo.code.equals(currencyState.info.code, ignoreCase = true)
                    }
            )

        if (
            inputInfo.currency is DisplayCurrency.Fiat &&
            inputInfo.code.equals(currencyState.info.code, ignoreCase = true)
        ) {
            currencyManager.ensureExchangeRateIfNeeded(inputInfo)
        }

        presetQuote?.let { paymentQuote ->
            val roundedAmount = paymentQuote.amountMsats
            if (
                roundedAmount < params.minSendable ||
                roundedAmount > params.maxSendable
            ) {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice(
                        "Preset amount is outside the allowed range"
                    )
                )
                return AdmissionResult.Presented
            }
            return if (session.requiresDetailsReview) {
                AdmissionResult.LnurlReview(
                    LnurlReviewRequest(
                        session = session,
                        amountMsats = roundedAmount,
                        isManualEntry = false,
                        paymentQuote = paymentQuote
                    )
                )
            } else {
                resolveLnurlInvoice(
                    session = session,
                    amountMsats = roundedAmount,
                    isManualEntry = false,
                    paymentQuote = paymentQuote,
                    token = token
                )
            }
        }

        if (params.minSendable == params.maxSendable) {
            return if (session.requiresDetailsReview) {
                AdmissionResult.LnurlReview(
                    LnurlReviewRequest(
                        session = session,
                        amountMsats = params.minSendable,
                        isManualEntry = false
                    )
                )
            } else {
                resolveLnurlInvoice(
                    session = session,
                    amountMsats = params.minSendable,
                    isManualEntry = false,
                    paymentQuote = null,
                    token = token
                )
            }
        }

        preparation.manualEntryContext = ManualEntryContext.Lnurl(session, inputInfo)
        val config =
            ManualAmountConfig(
                info = manualCurrencyState.info,
                exchangeRate = manualCurrencyState.exchangeRate,
                minMsats = params.minSendable,
                maxMsats = params.maxSendable
            )
        val entry = manualAmount.reset(config, clearInput = true)
        presentation.showManualAmount(entry, session.display)
        return AdmissionResult.Presented
    }

    private suspend fun resolveLnurlInvoice(
        session: LnurlSession,
        amountMsats: Long,
        isManualEntry: Boolean,
        paymentQuote: PaymentAmountQuote?,
        token: PaymentTaskToken
    ): AdmissionResult {
        presentation.showLoading()
        val result = preparation.resolveLnurlInvoice(session, amountMsats)
        token.ensureCurrent()
        return when (result) {
            is LnurlInvoiceResolution.Success ->
                handleLnurlInvoice(
                    session = session,
                    invoice = result.invoice,
                    isManualEntry = isManualEntry,
                    paymentQuote = paymentQuote
                )

            is LnurlInvoiceResolution.Failure -> {
                preparation.manualEntryContext = null
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private fun handleLnurlInvoice(
        session: LnurlSession,
        invoice: Bolt11Invoice,
        isManualEntry: Boolean,
        paymentQuote: PaymentAmountQuote?
    ): AdmissionResult {
        pendingTracker.findLatestByPaymentHash(invoice.paymentHash.toHex())?.let { existing ->
            preparation.manualEntryContext = null
            presentation.showActive()
            requestTransactionDetailNavigation(existing.id)
            return AdmissionResult.Presented
        }

        return AdmissionResult.Payment(
            PreparedPayment(
                invoice = invoice,
                amountOverrideMsats = null,
                origin =
                    if (isManualEntry) {
                        PendingOrigin.LnurlManual
                    } else {
                        PendingOrigin.LnurlFixed
                    },
                source = session.paymentSource,
                dynamicSourceKey = session.sourceKey,
                targetContext = session.targetContext,
                replacesDynamicGuardId = session.replacesDynamicGuardId,
                lnurlAuthorized = session.requiresDetailsReview,
                lnurlPayDisplay = session.display,
                paymentQuote = paymentQuote
            )
        )
    }

    private fun requestTransactionDetailNavigation(id: String) {
        pendingTracker.focus(id)
        presentation.requestTransactionDetailNavigation(id)
    }

    private fun rejectExpiredInvoice(invoice: Bolt11Invoice): Boolean {
        if (!invoice.isExpired(currentTimestampSeconds())) return false
        preparation.manualEntryContext = null
        presentation.presentError(PaymentUiError.InvalidInvoice("Invoice has expired"))
        return true
    }
}
