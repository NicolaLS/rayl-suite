package xyz.lilsus.rayl.providers.spark.application.payment

import xyz.lilsus.raylsuite.core.model.Satoshi

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
        val amountOverrideSats: Satoshi? = null,
        val comment: String? = null
    ) : Admission

    data class Rejected(val reason: PaymentRejection) : Admission
}

internal data class VerifiedDraft(
    val admission: Admission.Accepted,
    val prepared: SdkPreparedPayment,
    val projection: PreparedPayment,
    val origin: PaymentOrigin
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
