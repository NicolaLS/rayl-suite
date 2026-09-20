package xyz.lilsus.rayl.providers.spark.application.payment

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString.Companion.toByteString

class PaymentLinkInbox internal constructor(
    private val newId: () -> String = { Uuid.random().toString() }
) {
    private val mutableRevision = MutableStateFlow(0L)
    private var current: Entry? = null

    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun offer(rawUrl: String) {
        val entry = when (val admission = PaymentLinkAdapter.admit(rawUrl)) {
            is PaymentLinkAdmission.Accepted -> Entry(
                id = newId(),
                digest = admission.request.digest,
                content = EntryContent.Request(admission.request)
            )

            is PaymentLinkAdmission.Rejected -> Entry(
                id = newId(),
                digest = "rejected:${admission.reason}",
                content = EntryContent.Rejection(admission.reason)
            )
        }
        val active = current
        if (active?.claimed == true || active?.digest == entry.digest) {
            entry.clear()
            return
        }
        active?.clear()
        current = entry
        publish()
    }

    fun claim(): ClaimedPaymentLink? {
        val entry = current ?: return null
        val content = entry.content ?: return null
        if (!entry.claimed) {
            current = entry.copy(claimed = true)
        }
        return when (content) {
            is EntryContent.Request -> ClaimedPaymentLink.Request(entry.id, content.value)
            is EntryContent.Rejection -> ClaimedPaymentLink.Rejected(entry.id, content.reason)
        }
    }

    fun consume(id: String) {
        val entry = current?.takeIf { it.id == id } ?: return
        entry.clear()
        current = entry.copy(content = null, claimed = true)
    }

    fun finish(id: String) {
        val entry = current?.takeIf { it.id == id } ?: return
        entry.clear()
        current = null
    }

    fun clear() {
        current?.clear()
        current = null
    }

    override fun toString(): String = "PaymentLinkInbox(<redacted>)"

    private fun publish() {
        mutableRevision.value += 1
    }

    private data class Entry(
        val id: String,
        val digest: String,
        val content: EntryContent?,
        val claimed: Boolean = false
    ) {
        fun clear() {
            (content as? EntryContent.Request)?.value?.clear()
        }

        override fun toString(): String = "PaymentLinkEntry(<redacted>)"
    }
}

fun createPaymentLinkInbox(): PaymentLinkInbox = PaymentLinkInbox()

sealed interface ClaimedPaymentLink {
    val id: String

    class Request(override val id: String, private val value: SensitivePaymentRequest) :
        ClaimedPaymentLink {
        fun reveal(): String = value.reveal()
        override fun toString(): String = "ClaimedPaymentLink.Request(<redacted>)"
    }

    data class Rejected(override val id: String, val reason: PaymentLinkRejection) :
        ClaimedPaymentLink {
        override fun toString(): String = "ClaimedPaymentLink.Rejected(reason=$reason)"
    }
}

enum class PaymentLinkRejection {
    UNSUPPORTED_SCHEME,
    MALFORMED,
    TOO_LARGE
}

private sealed interface EntryContent {
    class Request(val value: SensitivePaymentRequest) : EntryContent
    data class Rejection(val reason: PaymentLinkRejection) : EntryContent
}

private sealed interface PaymentLinkAdmission {
    class Accepted(val request: SensitivePaymentRequest) : PaymentLinkAdmission
    data class Rejected(val reason: PaymentLinkRejection) : PaymentLinkAdmission
}

class SensitivePaymentRequest(private val bytes: ByteArray) {
    val digest: String = bytes.toByteString().sha256().hex()

    fun reveal(): String = bytes.decodeToString(throwOnInvalidSequence = true)

    fun clear() = bytes.fill(0)

    override fun toString(): String = "SensitivePaymentRequest(<redacted>)"
}

private object PaymentLinkAdapter {
    private const val LIGHTNING_SCHEME = "lightning"
    private const val LNURL_PAY_SCHEME = "lnurlp"
    private const val MAX_ENCODED_LENGTH = 8 * 1024

    fun admit(rawUrl: String): PaymentLinkAdmission {
        if (rawUrl.length > MAX_ENCODED_LENGTH) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.TOO_LARGE)
        }
        if (rawUrl.any { it.code <= 0x20 || it.code >= 0x7f }) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        }
        val separator = rawUrl.indexOf(':')
        if (separator <= 0) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.UNSUPPORTED_SCHEME)
        }
        val scheme = rawUrl.substring(0, separator).lowercase()
        val encoded = rawUrl.substring(separator + 1)
        return when (scheme) {
            LIGHTNING_SCHEME -> admitLightning(encoded)
            LNURL_PAY_SCHEME -> admitLnurlPay(rawUrl, encoded)
            else -> PaymentLinkAdmission.Rejected(PaymentLinkRejection.UNSUPPORTED_SCHEME)
        }
    }

    private fun admitLightning(encoded: String): PaymentLinkAdmission {
        if (encoded.isEmpty() || encoded.startsWith("//") || '#' in encoded) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        }
        val decoded = decodeOnce(encoded)
            ?: return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        if (decoded.isEmpty() ||
            decoded.startsWithHierarchicalPrefix() ||
            decoded.any { it == '#'.code.toByte() || it.toInt() <= 0x20 || it.toInt() >= 0x7f }
        ) {
            decoded.fill(0)
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        }
        val paymentRequest = decoded.decodeToString(throwOnInvalidSequence = true)
        if (!BOLT11_PREFIXES.any { paymentRequest.startsWith(it, ignoreCase = true) }) {
            decoded.fill(0)
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.UNSUPPORTED_SCHEME)
        }
        return PaymentLinkAdmission.Accepted(SensitivePaymentRequest(decoded))
    }

    private fun admitLnurlPay(rawUrl: String, encoded: String): PaymentLinkAdmission {
        if (!encoded.startsWith("//") || '#' in encoded ||
            !hasValidPercentEncoding(rawUrl)
        ) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        }
        val authority = encoded.substring(2).substringBefore('/').substringBefore('?')
        if (authority.isEmpty() || '@' in authority) {
            return PaymentLinkAdmission.Rejected(PaymentLinkRejection.MALFORMED)
        }
        return PaymentLinkAdmission.Accepted(SensitivePaymentRequest(rawUrl.encodeToByteArray()))
    }

    private fun decodeOnce(encoded: String): ByteArray? {
        val output = ByteArray(encoded.length)
        var inputIndex = 0
        var outputIndex = 0
        while (inputIndex < encoded.length) {
            val character = encoded[inputIndex]
            if (character == '%') {
                if (inputIndex + 2 >= encoded.length) return null
                val high = encoded[inputIndex + 1].hexValue() ?: return null
                val low = encoded[inputIndex + 2].hexValue() ?: return null
                output[outputIndex++] = ((high shl 4) or low).toByte()
                inputIndex += 3
            } else {
                output[outputIndex++] = character.code.toByte()
                inputIndex += 1
            }
        }
        val result = output.copyOf(outputIndex)
        output.fill(0)
        return try {
            result.decodeToString(throwOnInvalidSequence = true)
            result
        } catch (_: IllegalArgumentException) {
            result.fill(0)
            null
        }
    }

    private fun Char.hexValue(): Int? = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> null
    }

    private fun hasValidPercentEncoding(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length || value[index + 1].hexValue() == null ||
                    value[index + 2].hexValue() == null
                ) {
                    return false
                }
                index += 3
            } else {
                index += 1
            }
        }
        return true
    }

    private fun ByteArray.startsWithHierarchicalPrefix(): Boolean =
        size >= 2 && this[0] == '/'.code.toByte() && this[1] == '/'.code.toByte()

    private val BOLT11_PREFIXES = listOf("lnbcrt", "lntbs", "lnbc", "lntb")
}
