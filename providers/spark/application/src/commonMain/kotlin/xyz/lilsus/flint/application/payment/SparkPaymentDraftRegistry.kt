package xyz.lilsus.flint.application.payment

import xyz.lilsus.raylsuite.core.model.Satoshi

/**
 * Owns the coupled Spark draft handles and fingerprint aliases.
 *
 * Calls are serialized by [DefaultPaymentEngine]'s draft mutex so a handle cannot be cancelled
 * while the SDK is preparing or consuming it.
 */
internal class SparkPaymentDraftRegistry {
    private val drafts = mutableMapOf<String, VerifiedDraft>()
    private val amountDrafts = mutableMapOf<String, AmountDraft>()
    private val handlesByFingerprint = mutableMapOf<InvoiceFingerprint, String>()

    fun reusable(fingerprint: InvoiceFingerprint, nowEpochSeconds: Long): PreparePaymentResult? {
        val handle = handlesByFingerprint[fingerprint] ?: return null
        drafts[handle]?.takeUnless { it.isExpired(nowEpochSeconds) }?.let {
            return PreparePaymentResult.Ready(it.projection)
        }
        amountDrafts[handle]?.takeUnless { it.isExpired(nowEpochSeconds) }?.let {
            return PreparePaymentResult.AmountRequired(it.projection)
        }
        removeHandle(handle)
        return null
    }

    fun amount(handle: PaymentAmountHandle): AmountDraft? = amountDrafts[handle.value]

    fun draft(handle: PaymentDraftHandle): VerifiedDraft? = drafts[handle.value]

    fun registerAmount(handle: PaymentAmountHandle, draft: AmountDraft) {
        removeHandle(handle.value)
        amountDrafts[handle.value] = draft
        handlesByFingerprint[draft.admission.fingerprint] = handle.value
    }

    fun register(handle: PaymentDraftHandle, draft: VerifiedDraft) {
        removeHandle(handle.value)
        drafts[handle.value] = draft
        draft.fingerprints.forEach { handlesByFingerprint[it] = handle.value }
    }

    fun consume(handle: PaymentDraftHandle): VerifiedDraft? {
        val draft = drafts[handle.value] ?: return null
        removeHandle(handle.value)
        return draft
    }

    fun consumeAmount(handle: PaymentAmountHandle): AmountDraft? {
        val draft = amountDrafts[handle.value] ?: return null
        removeHandle(handle.value)
        return draft
    }

    fun clear() {
        drafts.clear()
        amountDrafts.clear()
        handlesByFingerprint.clear()
    }

    private fun removeHandle(handle: String) {
        drafts.remove(handle)
        amountDrafts.remove(handle)
        handlesByFingerprint
            .filterValues { it == handle }
            .keys
            .toList()
            .forEach(handlesByFingerprint::remove)
    }
}

internal sealed interface Admission {
    data class Accepted(
        val invoice: String,
        val fingerprint: InvoiceFingerprint,
        val method: PaymentMethod,
        val amountSats: Satoshi?,
        val expiresAtEpochSeconds: Long?,
        val minimumAmountSats: Satoshi = Satoshi.positive(1),
        val maximumAmountSats: Satoshi? = null,
        val lnurlRequest: ParsedSdkInput.LnurlPay? = null,
        val amountOverrideSats: Satoshi? = null
    ) : Admission

    data class Rejected(val reason: PaymentRejection) : Admission
}

internal data class VerifiedDraft(
    val admission: Admission.Accepted,
    val prepared: SdkPreparedPayment,
    val projection: PreparedPayment,
    val origin: PaymentOrigin,
    val fingerprints: Set<InvoiceFingerprint>
) {
    fun isExpired(nowEpochSeconds: Long): Boolean =
        admission.expiresAtEpochSeconds?.let { it <= nowEpochSeconds } ?: false
}

internal data class AmountDraft(
    val admission: Admission.Accepted,
    val projection: AmountRequiredPayment,
    val origin: PaymentOrigin
) {
    fun isExpired(nowEpochSeconds: Long): Boolean =
        admission.expiresAtEpochSeconds?.let { it <= nowEpochSeconds } ?: false
}
