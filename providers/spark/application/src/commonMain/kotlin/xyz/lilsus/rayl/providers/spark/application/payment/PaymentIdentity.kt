package xyz.lilsus.rayl.providers.spark.application.payment

import kotlin.time.Clock
import okio.ByteString.Companion.toByteString

enum class PaymentMethod {
    BOLT11,
    SPARK_INVOICE
}

enum class PaymentOrigin {
    DETECTED_CONTENT,
    DEEP_LINK
}

class InvoiceFingerprint private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is InvoiceFingerprint && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "InvoiceFingerprint(<redacted>)"

    companion object {
        fun bolt11(paymentHash: String): InvoiceFingerprint =
            InvoiceFingerprint("bolt11:${paymentHash.trim().lowercase()}")

        fun spark(invoice: String): InvoiceFingerprint = InvoiceFingerprint(
            "spark:${invoice.trim().lowercase().encodeToByteArray().toByteString().sha256().hex()}"
        )

        fun lnurl(destination: String): InvoiceFingerprint = InvoiceFingerprint(
            "lnurl:${destination.trim().encodeToByteArray().toByteString().sha256().hex()}"
        )
    }
}

fun currentEpochSeconds(): Long = Clock.System.now().epochSeconds
