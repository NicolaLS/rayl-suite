package xyz.lilsus.flint.application.payment

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.flint.application.AppEnvironment
import xyz.lilsus.raylsuite.core.model.Satoshi

class DefaultPaymentEngine(
    private val environment: AppEnvironment,
    private val operationTimeouts: PaymentOperationTimeouts = PaymentOperationTimeouts(),
    private val nowEpochSeconds: () -> Long = ::currentEpochSeconds,
    private val newPaymentId: () -> String = { Uuid.random().toString() }
) : PaymentEngine,
    PaymentSessionLifecycle {
    private val operationMutex = Mutex()
    private var amountDraft: AmountDraft? = null
    private var preparedDraft: VerifiedDraft? = null
    private val successfulPayments = mutableMapOf<InvoiceFingerprint, PaymentReceipt>()
    private val mutableConfirmationPolicy = MutableStateFlow(PaymentConfirmationPolicy.Default)
    private var client: SparkPaymentClient? = null

    override suspend fun attach(client: SparkPaymentClient) = operationMutex.withLock {
        if (this.client !== client) {
            clearSession()
            this.client = client
        }
    }

    override suspend fun detach() = operationMutex.withLock {
        client = null
        clearSession()
    }

    private fun clearSession() {
        clearDrafts()
        successfulPayments.clear()
    }

    private fun clearDrafts() {
        amountDraft = null
        preparedDraft = null
    }

    override suspend fun prepare(input: String, origin: PaymentOrigin): PreparePaymentResult =
        operationMutex.withLock {
            clearDrafts()
            val currentClient = client ?: return@withLock PreparePaymentResult.WalletUnavailable
            val now = nowEpochSeconds()
            val admitted = sdkResult(operationTimeouts.resolutionMillis) {
                admit(currentClient.parse(input), currentClient, now)
            } ?: return@withLock PreparePaymentResult.SdkFailure
            if (admitted is Admission.Rejected) {
                return@withLock PreparePaymentResult.Rejected(
                    admitted.reason
                )
            }
            admitted as Admission.Accepted

            successfulPayments[admitted.fingerprint]?.let {
                return@withLock PreparePaymentResult.Existing(it)
            }

            if (admitted.amountSats == null) {
                val handle = PaymentAmountHandle(Uuid.random().toString())
                val projection = AmountRequiredPayment(
                    handle = handle,
                    method = admitted.method,
                    expiresAtEpochSeconds = admitted.expiresAtEpochSeconds,
                    minimumAmountSats = admitted.minimumAmountSats,
                    maximumAmountSats = admitted.maximumAmountSats,
                    lnurlPayDetails = admitted.lnurlRequest?.reviewDetails
                )
                amountDraft = AmountDraft(
                    admission = admitted,
                    projection = projection,
                    origin = origin
                )
                return@withLock PreparePaymentResult.AmountRequired(projection)
            }
            val amountSats = admitted.amountSats

            var verifiedAdmission = admitted
            val preparation = sdkResult(operationTimeouts.preparationMillis) {
                admitted.lnurlRequest?.let { currentClient.prepareLnurl(it, amountSats) }
                    ?: currentClient.prepare(admitted.invoice, admitted.amountOverrideSats)
            } ?: return@withLock PreparePaymentResult.SdkFailure
            val prepared = when (preparation) {
                is SdkPreparationResult.Prepared -> preparation.payment

                SdkPreparationResult.InsufficientFunds ->
                    return@withLock PreparePaymentResult.Rejected(
                        PaymentRejection.INSUFFICIENT_FUNDS
                    )
            }
            val rejection = if (admitted.lnurlRequest != null) {
                verifyLnurlPreparation(admitted, prepared, nowEpochSeconds())
            } else {
                verifyPreparation(admitted, prepared)
            }
            rejection?.let {
                return@withLock PreparePaymentResult.Rejected(rejection)
            }
            if (admitted.lnurlRequest != null) {
                verifiedAdmission = admitted.copy(
                    fingerprint = checkNotNull(prepared.resolvedInvoiceFingerprint),
                    expiresAtEpochSeconds = checkNotNull(prepared.resolvedExpiresAtEpochSeconds)
                        .coerceAtMost(Long.MAX_VALUE.toULong())
                        .toLong(),
                    lnurlRequest = null
                )
            }
            val policy = mutableConfirmationPolicy.value

            val handle = PaymentDraftHandle(Uuid.random().toString())
            val projection = PreparedPayment(
                handle = handle,
                method = verifiedAdmission.method,
                amountSats = amountSats,
                feeSats = prepared.feeSats,
                expiresAtEpochSeconds = verifiedAdmission.expiresAtEpochSeconds,
                requiresConfirmation = policy.requiresConfirmation(
                    amountSats = amountSats,
                    feeSats = prepared.feeSats,
                    origin = origin,
                    amountEnteredByUser = false
                ),
                lnurlPayDetails = admitted.lnurlRequest?.reviewDetails
            )
            preparedDraft = VerifiedDraft(
                admission = verifiedAdmission,
                prepared = prepared,
                projection = projection,
                origin = origin
            )
            PreparePaymentResult.Ready(projection)
        }

    override suspend fun prepareAmount(
        handle: PaymentAmountHandle,
        amountSats: Satoshi
    ): PreparePaymentResult = operationMutex.withLock {
        val currentClient = client ?: return@withLock PreparePaymentResult.WalletUnavailable
        val amountDraft =
            this.amountDraft?.takeIf { it.projection.handle == handle }
                ?: return@withLock PreparePaymentResult.Rejected(
                    PaymentRejection.INVALID_AMOUNT
                )
        if (amountDraft.isExpired(nowEpochSeconds())) {
            this.amountDraft = null
            return@withLock PreparePaymentResult.Rejected(PaymentRejection.EXPIRED)
        }
        if (amountSats.value < amountDraft.projection.minimumAmountSats.value ||
            amountDraft.projection.maximumAmountSats?.let { amountSats.value > it.value } == true
        ) {
            return@withLock PreparePaymentResult.Rejected(PaymentRejection.INVALID_AMOUNT)
        }
        var admitted = amountDraft.admission.copy(amountSats = amountSats)
        val preparation = sdkResult(operationTimeouts.preparationMillis) {
            admitted.lnurlRequest?.let { currentClient.prepareLnurl(it, amountSats) }
                ?: currentClient.prepare(admitted.invoice, amountSats)
        } ?: return@withLock PreparePaymentResult.SdkFailure
        val prepared = when (preparation) {
            is SdkPreparationResult.Prepared -> preparation.payment

            SdkPreparationResult.InsufficientFunds -> return@withLock PreparePaymentResult.Rejected(
                PaymentRejection.INSUFFICIENT_FUNDS
            )
        }
        val rejection = if (admitted.lnurlRequest != null) {
            verifyLnurlPreparation(admitted, prepared, nowEpochSeconds())
        } else {
            verifyPreparation(admitted, prepared)
        }
        rejection?.let {
            return@withLock PreparePaymentResult.Rejected(rejection)
        }
        if (admitted.lnurlRequest != null) {
            admitted = admitted.copy(
                fingerprint = checkNotNull(prepared.resolvedInvoiceFingerprint),
                expiresAtEpochSeconds = checkNotNull(prepared.resolvedExpiresAtEpochSeconds)
                    .coerceAtMost(Long.MAX_VALUE.toULong())
                    .toLong(),
                lnurlRequest = null
            )
        }
        val policy = mutableConfirmationPolicy.value

        this.amountDraft = null
        val draftHandle = PaymentDraftHandle(handle.value)
        val projection = PreparedPayment(
            handle = draftHandle,
            method = admitted.method,
            amountSats = amountSats,
            feeSats = prepared.feeSats,
            expiresAtEpochSeconds = admitted.expiresAtEpochSeconds,
            requiresConfirmation = policy.requiresConfirmation(
                amountSats = amountSats,
                feeSats = prepared.feeSats,
                origin = amountDraft.origin,
                amountEnteredByUser = true
            ),
            lnurlPayDetails = amountDraft.admission.lnurlRequest?.reviewDetails
        )
        preparedDraft = VerifiedDraft(
            admission = admitted,
            prepared = prepared,
            projection = projection,
            origin = amountDraft.origin
        )
        PreparePaymentResult.Ready(projection)
    }

    override suspend fun updateConfirmationPolicy(policy: PaymentConfirmationPolicy) {
        mutableConfirmationPolicy.value = policy
    }

    override suspend fun cancel(handle: PaymentDraftHandle) = operationMutex.withLock {
        if (preparedDraft?.projection?.handle == handle) preparedDraft = null
    }

    override suspend fun cancel(handle: PaymentAmountHandle) = operationMutex.withLock {
        if (amountDraft?.projection?.handle == handle) amountDraft = null
    }

    override suspend fun autoPay(handle: PaymentDraftHandle): ConfirmPaymentResult =
        submit(handle, explicitlyConfirmed = false)

    override suspend fun confirm(handle: PaymentDraftHandle): ConfirmPaymentResult =
        submit(handle, explicitlyConfirmed = true)

    private suspend fun submit(
        handle: PaymentDraftHandle,
        explicitlyConfirmed: Boolean
    ): ConfirmPaymentResult = operationMutex.withLock {
        val draft = preparedDraft?.takeIf { it.projection.handle == handle }
            ?: return@withLock ConfirmPaymentResult.DraftUnavailable
        if (!explicitlyConfirmed && draft.projection.requiresConfirmation) {
            return@withLock ConfirmPaymentResult.ConfirmationRequired
        }
        preparedDraft = null
        if (draft.isExpired(nowEpochSeconds())) {
            return@withLock ConfirmPaymentResult.DraftUnavailable
        }
        val currentClient = client ?: return@withLock ConfirmPaymentResult.WalletUnavailable
        successfulPayments[draft.admission.fingerprint]?.let {
            return@withLock ConfirmPaymentResult.Completed(it, wasAlreadyPaid = true)
        }
        val id = newPaymentId()
        val createdAt = nowEpochSeconds()
        var payment = sdkAttempt { currentClient.send(draft.prepared, id) }
            ?: return@withLock ConfirmPaymentResult.OutcomeUnknown
        val paymentId = payment.id
        if (paymentId.isBlank()) return@withLock ConfirmPaymentResult.OutcomeUnknown
        if (payment.status == SdkPaymentStatus.PENDING) {
            payment = sdkAttempt {
                awaitPayment(currentClient, paymentId)
            } ?: return@withLock ConfirmPaymentResult.OutcomeUnknown
        }
        val receipt = PaymentReceipt(
            id = id,
            method = draft.admission.method,
            amountSats = checkNotNull(draft.admission.amountSats),
            feeSats = draft.prepared.feeSats,
            origin = draft.origin,
            createdAtEpochSeconds = createdAt,
            outcome = when (payment.status) {
                SdkPaymentStatus.COMPLETED -> PaymentOutcome.COMPLETED
                SdkPaymentStatus.FAILED -> PaymentOutcome.FAILED
                SdkPaymentStatus.PENDING -> error("A receipt requires a terminal payment")
            }
        )
        if (receipt.outcome == PaymentOutcome.COMPLETED) {
            successfulPayments[draft.admission.fingerprint] = receipt
        }
        ConfirmPaymentResult.Completed(receipt)
    }

    private suspend fun awaitPayment(client: SparkPaymentClient, paymentId: String): SdkPayment? {
        val completion = CompletableDeferred<SdkPayment>()
        var stopObserving: (suspend () -> Unit)? = null
        try {
            // Capture cleanup before cancellation can discard a registered listener.
            withContext(NonCancellable) {
                stopObserving = client.observePayments { payment ->
                    if (payment.id == paymentId && payment.status != SdkPaymentStatus.PENDING) {
                        completion.complete(payment)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            // A terminal event may have arrived before the listener was registered.
            val payment = sdkResult(operationTimeouts.lookupMillis) {
                client.getPayment(paymentId)
            }
            if (completion.isCompleted) return completion.await()
            if (payment == null || payment.id != paymentId) return null
            if (payment.status != SdkPaymentStatus.PENDING) return payment
            return completion.await()
        } finally {
            withContext(NonCancellable) {
                stopObserving?.invoke()
            }
        }
    }

    private suspend fun admit(
        parsed: ParsedSdkInput,
        client: SparkPaymentClient,
        now: Long
    ): Admission = when (parsed) {
        ParsedSdkInput.Unsupported -> Admission.Rejected(PaymentRejection.UNSUPPORTED_INPUT)

        ParsedSdkInput.OnChain -> Admission.Rejected(PaymentRejection.ON_CHAIN_NOT_ALLOWED)

        is ParsedSdkInput.Bolt11 -> {
            val amountMsat = parsed.amountMsat
            if (amountMsat != null &&
                (
                    amountMsat == 0uL || amountMsat % 1_000uL != 0uL ||
                        amountMsat / 1_000uL > Long.MAX_VALUE.toULong()
                    )
            ) {
                return Admission.Rejected(PaymentRejection.INVALID_AMOUNT)
            }
            if (!networkMatches(
                    parsed.network
                )
            ) {
                return Admission.Rejected(PaymentRejection.WRONG_NETWORK)
            }
            if (parsed.expiresAtEpochSeconds <=
                now.toULong()
            ) {
                return Admission.Rejected(PaymentRejection.EXPIRED)
            }
            Admission.Accepted(
                invoice = parsed.invoice,
                fingerprint = InvoiceFingerprint.bolt11(parsed.paymentHash),
                method = PaymentMethod.BOLT11,
                amountSats = amountMsat?.let { Satoshi.positive((it / 1_000uL).toLong()) },
                expiresAtEpochSeconds = parsed.expiresAtEpochSeconds.coerceAtMost(
                    Long.MAX_VALUE.toULong()
                ).toLong(),
                amountOverrideSats = parsed.amountOverrideSats?.let(Satoshi::positive)
            )
        }

        is ParsedSdkInput.SparkInvoice -> {
            val amount = parsed.amountSats
            if (amount != null &&
                amount <= 0
            ) {
                return Admission.Rejected(PaymentRejection.INVALID_AMOUNT)
            }
            if (parsed.tokenIdentifier !=
                null
            ) {
                return Admission.Rejected(PaymentRejection.TOKEN_NOT_ALLOWED)
            }
            if (!networkMatches(
                    parsed.network
                )
            ) {
                return Admission.Rejected(PaymentRejection.WRONG_NETWORK)
            }
            if (parsed.expiryTimeEpochSeconds != null &&
                parsed.expiryTimeEpochSeconds <= now.toULong()
            ) {
                return Admission.Rejected(PaymentRejection.EXPIRED)
            }
            if (parsed.senderPublicKey != null &&
                !parsed.senderPublicKey.equals(client.identityPublicKey(), ignoreCase = true)
            ) {
                return Admission.Rejected(PaymentRejection.SENDER_NOT_ALLOWED)
            }
            Admission.Accepted(
                invoice = parsed.invoice,
                fingerprint = InvoiceFingerprint.spark(parsed.invoice),
                method = PaymentMethod.SPARK_INVOICE,
                amountSats = amount?.let(Satoshi::positive),
                expiresAtEpochSeconds = parsed.expiryTimeEpochSeconds
                    ?.coerceAtMost(Long.MAX_VALUE.toULong())
                    ?.toLong(),
                amountOverrideSats = parsed.amountOverrideSats?.let(Satoshi::positive)
            )
        }

        is ParsedSdkInput.LnurlPay -> {
            val bounds = lnurlBounds(parsed.minSendableMsat, parsed.maxSendableMsat)
                ?: return Admission.Rejected(PaymentRejection.INVALID_AMOUNT)
            val amount = parsed.amountOverrideSats?.let {
                if (it <= 0 || it < bounds.first.value || it > bounds.second.value) {
                    return Admission.Rejected(PaymentRejection.INVALID_AMOUNT)
                }
                Satoshi.positive(it)
            } ?: bounds.first.takeIf { it == bounds.second }
            Admission.Accepted(
                invoice = "",
                fingerprint = parsed.requestFingerprint,
                method = PaymentMethod.BOLT11,
                amountSats = amount,
                expiresAtEpochSeconds = null,
                minimumAmountSats = bounds.first,
                maximumAmountSats = bounds.second,
                lnurlRequest = parsed,
                amountOverrideSats = amount
            )
        }
    }

    private fun verifyPreparation(
        admission: Admission.Accepted,
        prepared: SdkPreparedPayment
    ): PaymentRejection? = when {
        prepared.method != admission.method -> PaymentRejection.METHOD_MISMATCH
        prepared.amountSats != admission.amountSats -> PaymentRejection.INVALID_AMOUNT
        prepared.tokenIdentifier != null -> PaymentRejection.TOKEN_NOT_ALLOWED
        prepared.hasConversion -> PaymentRejection.CONVERSION_NOT_ALLOWED
        else -> null
    }

    private fun verifyLnurlPreparation(
        admission: Admission.Accepted,
        prepared: SdkPreparedPayment,
        nowEpochSeconds: Long
    ): PaymentRejection? = when {
        prepared.method != PaymentMethod.BOLT11 -> PaymentRejection.METHOD_MISMATCH

        prepared.amountSats != admission.amountSats -> PaymentRejection.INVALID_AMOUNT

        prepared.tokenIdentifier != null -> PaymentRejection.TOKEN_NOT_ALLOWED

        prepared.hasConversion -> PaymentRejection.CONVERSION_NOT_ALLOWED

        prepared.resolvedInvoiceFingerprint == null -> PaymentRejection.UNSUPPORTED_INPUT

        prepared.resolvedNetwork == null || !networkMatches(
            prepared.resolvedNetwork
        ) -> PaymentRejection.WRONG_NETWORK

        prepared.resolvedExpiresAtEpochSeconds == null ||
            prepared.resolvedExpiresAtEpochSeconds <= nowEpochSeconds.toULong() ->
            PaymentRejection.EXPIRED

        else -> null
    }

    private fun lnurlBounds(
        minSendableMsat: ULong,
        maxSendableMsat: ULong
    ): Pair<Satoshi, Satoshi>? {
        if (minSendableMsat > maxSendableMsat) return null
        val minimumWholeSats =
            minSendableMsat / 1_000uL + if (minSendableMsat % 1_000uL == 0uL) 0uL else 1uL
        val maximumWholeSats = maxSendableMsat / 1_000uL
        if (maximumWholeSats == 0uL ||
            minimumWholeSats > Long.MAX_VALUE.toULong() ||
            maximumWholeSats > Long.MAX_VALUE.toULong()
        ) {
            return null
        }
        val minimum = minimumWholeSats.coerceAtLeast(1uL)
        if (minimum > maximumWholeSats) return null
        return Satoshi.positive(minimum.toLong()) to Satoshi.positive(maximumWholeSats.toLong())
    }

    private fun networkMatches(network: PaymentNetwork): Boolean = when (environment) {
        AppEnvironment.DEBUG -> network == PaymentNetwork.REGTEST
        AppEnvironment.PRODUCTION -> network == PaymentNetwork.MAINNET
    }

    private suspend fun <T> sdkResult(timeoutMillis: Long, block: suspend () -> T): T? = try {
        currentCoroutineContext().ensureActive()
        val result = withTimeoutOrNull(timeoutMillis) { block() }
        currentCoroutineContext().ensureActive()
        result
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun <T> sdkAttempt(block: suspend () -> T): T? = try {
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        result
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        currentCoroutineContext().ensureActive()
        null
    }

    data class PaymentOperationTimeouts(
        val resolutionMillis: Long = 10_000,
        val preparationMillis: Long = 20_000,
        val lookupMillis: Long = 10_000
    ) {
        init {
            require(resolutionMillis > 0)
            require(preparationMillis > 0)
            require(lookupMillis > 0)
        }
    }
}
