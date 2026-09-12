package xyz.lilsus.flint.application.payment

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.flint.application.AppEnvironment
import xyz.lilsus.raylsuite.core.model.Satoshi

class DefaultPaymentEngine(
    private val environment: AppEnvironment,
    private val repository: PaymentAttemptRepository,
    private val applicationScope: CoroutineScope,
    private val operationTimeouts: PaymentOperationTimeouts = PaymentOperationTimeouts(),
    private val nowEpochSeconds: () -> Long = ::currentEpochSeconds,
    private val newAttemptId: () -> String = { Uuid.random().toString() }
) : PaymentEngine,
    PaymentSessionLifecycle {
    private val draftMutex = Mutex()
    private val submissionMutex = Mutex()
    private val sessionMutex = Mutex()
    private val refreshMutex = Mutex()
    private val policyMutex = Mutex()
    private val draftRegistry = SparkPaymentDraftRegistry()
    private val paymentsById = mutableMapOf<String, SdkPayment>()
    private val mutableActivity = MutableStateFlow<List<PaymentActivity>>(emptyList())
    private val mutableConfirmationPolicy = MutableStateFlow(PaymentConfirmationPolicy.Default)
    private var client: SparkPaymentClient? = null
    private var listenerId: String? = null
    private var sessionGeneration = 0L

    override val activity: StateFlow<List<PaymentActivity>> = mutableActivity.asStateFlow()
    override val confirmationPolicy: StateFlow<PaymentConfirmationPolicy> =
        mutableConfirmationPolicy.asStateFlow()

    override suspend fun attach(client: SparkPaymentClient) {
        val attached = sessionMutex.withLock {
            if (this.client === client) return@withLock false
            detachLocked()
            this.client = client
            sessionGeneration += 1
            val listenerGeneration = sessionGeneration
            listenerId = client.addEventListener { event ->
                when (event) {
                    is SparkPaymentEvent.PaymentChanged ->
                        onPaymentChanged(event.payment, client, listenerGeneration)

                    SparkPaymentEvent.Synced -> requestRefresh()
                }
            }
            true
        }
        if (attached) refresh()
    }

    override suspend fun detach() = sessionMutex.withLock { detachLocked() }

    override suspend fun clearWalletData(): Boolean = submissionMutex.withLock {
        draftMutex.withLock {
            if (storageAttempt { repository.clear() }.isFailure) return@withLock false
            draftRegistry.clear()
            paymentsById.clear()
            mutableActivity.value = emptyList()
            true
        }
    }

    override suspend fun prepare(input: String, origin: PaymentOrigin): PreparePaymentResult =
        draftMutex.withLock {
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

            if (origin != PaymentOrigin.MANUAL_RECOVERY) {
                val existing = try {
                    repository.findByFingerprint(admitted.fingerprint)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return@withLock PreparePaymentResult.StorageFailure
                }
                existing?.let {
                    if (it.linkPhase == PaymentLinkPhase.SDK_PAYMENT_LINKED) {
                        refreshLinked(it, currentClient)
                    }
                    publishActivity()
                    return@withLock PreparePaymentResult.Existing(activityFor(it))
                }
            }

            draftRegistry.reusable(admitted.fingerprint, now)?.let { return@withLock it }

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
                draftRegistry.registerAmount(
                    handle,
                    AmountDraft(
                        admission = admitted,
                        projection = projection,
                        origin = origin
                    )
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
            val policy = currentPolicy()

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
            draftRegistry.register(
                handle,
                VerifiedDraft(
                    admission = verifiedAdmission,
                    prepared = prepared,
                    projection = projection,
                    origin = origin,
                    fingerprints = setOf(admitted.fingerprint, verifiedAdmission.fingerprint)
                )
            )
            PreparePaymentResult.Ready(projection)
        }

    override suspend fun prepareAmount(
        handle: PaymentAmountHandle,
        amountSats: Satoshi
    ): PreparePaymentResult = prepareAmountInternal(handle, amountSats)

    private suspend fun prepareAmountInternal(
        handle: PaymentAmountHandle,
        amountSats: Satoshi
    ): PreparePaymentResult = draftMutex.withLock {
        val currentClient = client ?: return@withLock PreparePaymentResult.WalletUnavailable
        val amountDraft =
            draftRegistry.amount(handle) ?: return@withLock PreparePaymentResult.Rejected(
                PaymentRejection.INVALID_AMOUNT
            )
        if (amountDraft.isExpired(nowEpochSeconds())) {
            draftRegistry.consumeAmount(handle)
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
        val policy = currentPolicy()

        draftRegistry.consumeAmount(handle)
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
        draftRegistry.register(
            draftHandle,
            VerifiedDraft(
                admission = admitted,
                prepared = prepared,
                projection = projection,
                origin = amountDraft.origin,
                fingerprints = setOf(amountDraft.admission.fingerprint, admitted.fingerprint)
            )
        )
        PreparePaymentResult.Ready(projection)
    }

    override suspend fun updateConfirmationPolicy(policy: PaymentConfirmationPolicy) =
        policyMutex.withLock {
            mutableConfirmationPolicy.value = policy
        }

    override suspend fun cancel(handle: PaymentDraftHandle) = draftMutex.withLock {
        draftRegistry.consume(handle)
        Unit
    }

    override suspend fun cancel(handle: PaymentAmountHandle) = draftMutex.withLock {
        draftRegistry.consumeAmount(handle)
        Unit
    }

    override suspend fun autoPay(handle: PaymentDraftHandle): ConfirmPaymentResult =
        submit(handle, explicitlyConfirmed = false)

    override suspend fun confirm(handle: PaymentDraftHandle): ConfirmPaymentResult =
        submit(handle, explicitlyConfirmed = true)

    private suspend fun submit(
        handle: PaymentDraftHandle,
        explicitlyConfirmed: Boolean
    ): ConfirmPaymentResult = submissionMutex.withLock {
        var confirmationRequired = false
        val draft = draftMutex.withLock {
            val existing = draftRegistry.draft(handle) ?: return@withLock null
            if (!explicitlyConfirmed && existing.projection.requiresConfirmation) {
                confirmationRequired = true
                null
            } else {
                draftRegistry.consume(handle)
            }
        }
        if (confirmationRequired) return@withLock ConfirmPaymentResult.ConfirmationRequired
        draft ?: return@withLock ConfirmPaymentResult.DraftUnavailable
        if (draft.isExpired(
                nowEpochSeconds()
            )
        ) {
            return@withLock ConfirmPaymentResult.DraftUnavailable
        }
        val currentClient = client ?: return@withLock ConfirmPaymentResult.WalletUnavailable
        val now = nowEpochSeconds()

        val existingAttempt = storageAttempt {
            repository.findByFingerprint(draft.admission.fingerprint)
        }
            .getOrElse { return@withLock ConfirmPaymentResult.PersistenceFailed }
        val attempt = when (val existing = existingAttempt) {
            null -> when (
                val created = repository.createConfirmed(
                    attemptId = newAttemptId(),
                    fingerprint = draft.admission.fingerprint,
                    method = draft.admission.method,
                    amountSats = checkNotNull(draft.admission.amountSats),
                    feeSats = draft.prepared.feeSats,
                    origin = draft.origin,
                    nowEpochSeconds = now
                )
            ) {
                is CreateAttemptResult.Created -> created.attempt

                is CreateAttemptResult.Existing -> created.attempt

                CreateAttemptResult.CapacityReached ->
                    return@withLock ConfirmPaymentResult.CapacityReached

                CreateAttemptResult.Failed -> return@withLock ConfirmPaymentResult.PersistenceFailed
            }

            else -> existing
        }

        if (attempt.linkPhase == PaymentLinkPhase.SDK_PAYMENT_LINKED) {
            refreshLinked(attempt, currentClient)
            return@withLock ConfirmPaymentResult.Submitted(activityFor(attempt))
        }
        val submissionAttempt = if (attempt.linkPhase == PaymentLinkPhase.CONFIRMED) {
            val startedAt = nowEpochSeconds()
            val started = storageAttempt {
                repository.markSubmissionStarted(attempt.attemptId, startedAt)
            }
                .getOrDefault(false)
            if (!started) return@withLock ConfirmPaymentResult.PersistenceFailed
            attempt.copy(
                updatedAtEpochSeconds = startedAt,
                linkPhase = PaymentLinkPhase.SUBMISSION_STARTED
            )
        } else {
            attempt
        }
        publishActivity()

        val payment = sdkResult(operationTimeouts.submissionMillis) {
            currentClient.send(draft.prepared, attempt.attemptId)
        }
        if (payment != null) {
            val linkedAt = nowEpochSeconds()
            if (!storageAttempt {
                    repository.linkPayment(attempt.attemptId, payment.id, linkedAt)
                }.getOrDefault(false)
            ) {
                publishActivity()
                return@withLock ConfirmPaymentResult.Submitted(activityFor(submissionAttempt))
            }
            cachePayment(payment)
            publishActivity()
            val linkedAttempt = submissionAttempt.copy(
                updatedAtEpochSeconds = linkedAt,
                linkPhase = PaymentLinkPhase.SDK_PAYMENT_LINKED,
                breezPaymentId = payment.id
            )
            ConfirmPaymentResult.Submitted(activityFor(linkedAttempt))
        } else {
            publishActivity()
            ConfirmPaymentResult.Submitted(activityFor(submissionAttempt))
        }
    }

    private suspend fun currentPolicy(): PaymentConfirmationPolicy =
        policyMutex.withLock { mutableConfirmationPolicy.value }

    override fun requestRefresh() {
        applicationScope.launch {
            try {
                refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {}
        }
    }

    override suspend fun refresh() = refreshMutex.withLock {
        val session = sessionMutex.withLock {
            val currentClient = client ?: return@withLock null
            val linked = repository.linked().filter { attempt ->
                val status = attempt.breezPaymentId?.let(paymentsById::get)?.status
                status !in setOf(SdkPaymentStatus.COMPLETED, SdkPaymentStatus.FAILED)
            }
            PaymentSessionSnapshot(
                currentClient,
                sessionGeneration,
                linked,
                repository.unresolved()
            )
        } ?: return@withLock

        session.linked.forEach { refreshLinked(it, session) }
        session.unresolved.forEach { recoverExact(it, session) }
        withCurrentSession(session) { publishActivity() }
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

    private suspend fun refreshLinked(attempt: PaymentAttempt, session: PaymentSessionSnapshot) {
        val paymentId = attempt.breezPaymentId ?: return
        val payment =
            sdkResult(operationTimeouts.lookupMillis) { session.client.getPayment(paymentId) }
                ?: return
        withCurrentSession(session) { cachePayment(payment) }
    }

    private suspend fun refreshLinked(attempt: PaymentAttempt, client: SparkPaymentClient) {
        val paymentId = attempt.breezPaymentId ?: return
        sdkResult(operationTimeouts.lookupMillis) {
            client.getPayment(paymentId)
        }?.let(::cachePayment)
    }

    private suspend fun recoverExact(attempt: PaymentAttempt, session: PaymentSessionSnapshot) {
        val exact = sdkResult(operationTimeouts.lookupMillis) {
            session.client.listSentPayments(
                attempt.method,
                attempt.createdAtEpochSeconds
            ).filter { candidate ->
                candidate.method == attempt.method && candidate.invoice?.let { invoice ->
                    sdkAttempt { fingerprint(session.client.parse(invoice)) == attempt.fingerprint }
                        ?: false
                } == true
            }
        } ?: return
        if (exact.size == 1) {
            withCurrentSession(session) {
                if (repository.linkPayment(
                        attempt.attemptId,
                        exact.single().id,
                        nowEpochSeconds()
                    )
                ) {
                    cachePayment(exact.single())
                }
            }
        }
    }

    private fun fingerprint(input: ParsedSdkInput): InvoiceFingerprint? = when (input) {
        is ParsedSdkInput.Bolt11 -> InvoiceFingerprint.bolt11(input.paymentHash)
        is ParsedSdkInput.SparkInvoice -> InvoiceFingerprint.spark(input.invoice)
        is ParsedSdkInput.LnurlPay -> input.requestFingerprint
        ParsedSdkInput.OnChain -> null
        ParsedSdkInput.Unsupported -> null
    }

    private suspend fun onPaymentChanged(
        payment: SdkPayment,
        eventClient: SparkPaymentClient,
        eventGeneration: Long
    ) {
        sessionMutex.withLock {
            if (client !== eventClient || sessionGeneration != eventGeneration) return@withLock
            if (repository.linked().any { it.breezPaymentId == payment.id }) {
                cachePayment(payment)
                publishActivity()
            }
        }
    }

    private suspend fun withCurrentSession(session: PaymentSessionSnapshot, block: () -> Unit) {
        sessionMutex.withLock {
            if (client === session.client && sessionGeneration == session.generation) block()
        }
    }

    private fun publishActivity(): Boolean {
        val attempts = storageAttempt { repository.all() }.getOrNull() ?: return false
        mutableActivity.value = attempts.map(::activityFor)
        return true
    }

    private fun activityFor(attempt: PaymentAttempt): PaymentActivity = PaymentActivity(
        attemptId = attempt.attemptId,
        method = attempt.method,
        amountSats = attempt.amountSats,
        feeSats = attempt.feeSats,
        origin = attempt.origin,
        createdAtEpochSeconds = attempt.createdAtEpochSeconds,
        outcome = when (attempt.linkPhase) {
            PaymentLinkPhase.CONFIRMED -> PaymentOutcome.CONFIRMATION_RECORDED

            PaymentLinkPhase.SUBMISSION_STARTED -> PaymentOutcome.SUBMISSION_UNRESOLVED

            PaymentLinkPhase.SDK_PAYMENT_LINKED ->
                attempt.breezPaymentId
                    ?.let(paymentsById::get)
                    ?.status
                    ?.toOutcome()
                    ?: PaymentOutcome.STATUS_UNAVAILABLE
        }
    )

    private fun SdkPaymentStatus.toOutcome(): PaymentOutcome = when (this) {
        SdkPaymentStatus.PENDING -> PaymentOutcome.PENDING
        SdkPaymentStatus.COMPLETED -> PaymentOutcome.COMPLETED
        SdkPaymentStatus.FAILED -> PaymentOutcome.FAILED
    }

    private fun cachePayment(payment: SdkPayment) {
        val existing = paymentsById[payment.id]
        if (existing?.status in setOf(SdkPaymentStatus.COMPLETED, SdkPaymentStatus.FAILED) &&
            payment.status == SdkPaymentStatus.PENDING
        ) {
            return
        }
        paymentsById[payment.id] = payment
    }

    private suspend fun <T> sdkResult(timeoutMillis: Long, block: suspend () -> T): T? = try {
        withTimeoutOrNull(timeoutMillis) { block() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private suspend fun <T> sdkAttempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private inline fun <T> storageAttempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun detachLocked() {
        val previousClient = client
        val previousListener = listenerId
        client = null
        listenerId = null
        sessionGeneration += 1
        try {
            if (previousClient != null && previousListener != null) {
                try {
                    previousClient.removeEventListener(previousListener)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {}
            }
        } finally {
            paymentsById.clear()
            publishActivity()
        }
    }

    private data class PaymentSessionSnapshot(
        val client: SparkPaymentClient,
        val generation: Long,
        val linked: List<PaymentAttempt>,
        val unresolved: List<PaymentAttempt>
    )

    data class PaymentOperationTimeouts(
        val resolutionMillis: Long = 10_000,
        val preparationMillis: Long = 20_000,
        val submissionMillis: Long = 20_000,
        val lookupMillis: Long = 10_000
    ) {
        init {
            require(resolutionMillis > 0)
            require(preparationMillis > 0)
            require(submissionMillis > 0)
            require(lookupMillis > 0)
        }
    }
}
