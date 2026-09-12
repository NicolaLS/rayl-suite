package xyz.lilsus.flint.application.payment

import kotlinx.coroutines.flow.StateFlow
import xyz.lilsus.raylsuite.core.model.Satoshi

interface PaymentEngine {
    val activity: StateFlow<List<PaymentActivity>>
    val confirmationPolicy: StateFlow<PaymentConfirmationPolicy>

    suspend fun prepare(input: String, origin: PaymentOrigin): PreparePaymentResult
    suspend fun prepareAmount(
        handle: PaymentAmountHandle,
        amountSats: Satoshi
    ): PreparePaymentResult
    suspend fun updateConfirmationPolicy(policy: PaymentConfirmationPolicy)
    suspend fun cancel(handle: PaymentDraftHandle)
    suspend fun cancel(handle: PaymentAmountHandle)
    suspend fun autoPay(handle: PaymentDraftHandle): ConfirmPaymentResult
    suspend fun confirm(handle: PaymentDraftHandle): ConfirmPaymentResult
    fun requestRefresh()
    suspend fun refresh()
}

class PaymentAmountHandle internal constructor(internal val value: String) {
    override fun equals(other: Any?): Boolean = other is PaymentAmountHandle && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "PaymentAmountHandle(<opaque>)"
}

class PaymentDraftHandle internal constructor(internal val value: String) {
    override fun equals(other: Any?): Boolean = other is PaymentDraftHandle && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "PaymentDraftHandle(<opaque>)"
}

data class PreparedPayment(
    val handle: PaymentDraftHandle,
    val method: PaymentMethod,
    val amountSats: Satoshi,
    val feeSats: Satoshi,
    val expiresAtEpochSeconds: Long?,
    val requiresConfirmation: Boolean = true,
    val lnurlPayDetails: LnurlPayReviewDetails? = null
)

data class AmountRequiredPayment(
    val handle: PaymentAmountHandle,
    val method: PaymentMethod,
    val expiresAtEpochSeconds: Long?,
    val minimumAmountSats: Satoshi = Satoshi.positive(1),
    val maximumAmountSats: Satoshi? = null,
    val lnurlPayDetails: LnurlPayReviewDetails? = null
)

data class LnurlPayReviewDetails(
    val domain: String,
    val description: String?,
    val imagePngBase64: String?,
    val imageJpegBase64: String?
)

sealed interface PreparePaymentResult {
    data class AmountRequired(val payment: AmountRequiredPayment) : PreparePaymentResult
    data class Ready(val payment: PreparedPayment) : PreparePaymentResult
    data class Existing(val activity: PaymentActivity) : PreparePaymentResult
    data class Rejected(val reason: PaymentRejection) : PreparePaymentResult
    data object WalletUnavailable : PreparePaymentResult
    data object SdkFailure : PreparePaymentResult
    data object StorageFailure : PreparePaymentResult
}

enum class PaymentRejection {
    UNSUPPORTED_INPUT,
    ON_CHAIN_NOT_ALLOWED,
    AMOUNT_REQUIRED,
    INVALID_AMOUNT,
    EXPIRED,
    WRONG_NETWORK,
    TOKEN_NOT_ALLOWED,
    SENDER_NOT_ALLOWED,
    METHOD_MISMATCH,
    CONVERSION_NOT_ALLOWED,
    INSUFFICIENT_FUNDS
}

sealed interface ConfirmPaymentResult {
    data class Submitted(val activity: PaymentActivity) : ConfirmPaymentResult
    data object ConfirmationRequired : ConfirmPaymentResult
    data object DraftUnavailable : ConfirmPaymentResult
    data object WalletUnavailable : ConfirmPaymentResult
    data object PersistenceFailed : ConfirmPaymentResult
    data object CapacityReached : ConfirmPaymentResult
}

data class PaymentActivity(
    val attemptId: String,
    val method: PaymentMethod,
    val amountSats: Satoshi,
    val feeSats: Satoshi,
    val origin: PaymentOrigin,
    val createdAtEpochSeconds: Long,
    val outcome: PaymentOutcome
)

enum class PaymentOutcome {
    CONFIRMATION_RECORDED,
    SUBMISSION_UNRESOLVED,
    STATUS_UNAVAILABLE,
    PENDING,
    COMPLETED,
    FAILED
}

interface PaymentSessionLifecycle {
    suspend fun attach(client: SparkPaymentClient)
    suspend fun detach()
    suspend fun clearWalletData(): Boolean
}

enum class PaymentNetwork {
    MAINNET,
    REGTEST,
    OTHER
}

sealed interface ParsedSdkInput {
    data class Bolt11(
        val invoice: String,
        val paymentHash: String,
        val amountMsat: ULong?,
        val network: PaymentNetwork,
        val expiresAtEpochSeconds: ULong,
        val amountOverrideSats: Long? = null
    ) : ParsedSdkInput

    data class SparkInvoice(
        val invoice: String,
        val amountSats: Long?,
        val network: PaymentNetwork,
        val expiryTimeEpochSeconds: ULong?,
        val tokenIdentifier: String?,
        val senderPublicKey: String?,
        val amountOverrideSats: Long? = null
    ) : ParsedSdkInput

    class LnurlPay(
        val requestFingerprint: InvoiceFingerprint,
        val minSendableMsat: ULong,
        val maxSendableMsat: ULong,
        val reviewDetails: LnurlPayReviewDetails,
        val payload: LnurlPayRequestPayload,
        val amountOverrideSats: Long? = null
    ) : ParsedSdkInput {
        override fun toString(): String = "LnurlPay(<redacted>)"
    }

    data object OnChain : ParsedSdkInput
    data object Unsupported : ParsedSdkInput
}

interface PreparedSdkPayload

interface LnurlPayRequestPayload

data class SdkPreparedPayment(
    val payload: PreparedSdkPayload,
    val method: PaymentMethod,
    val amountSats: Satoshi,
    val feeSats: Satoshi,
    val tokenIdentifier: String?,
    val hasConversion: Boolean,
    val resolvedInvoiceFingerprint: InvoiceFingerprint? = null,
    val resolvedNetwork: PaymentNetwork? = null,
    val resolvedExpiresAtEpochSeconds: ULong? = null
)

sealed interface SdkPreparationResult {
    data class Prepared(val payment: SdkPreparedPayment) : SdkPreparationResult
    data object InsufficientFunds : SdkPreparationResult
}

enum class SdkPaymentStatus {
    PENDING,
    COMPLETED,
    FAILED
}

data class SdkPayment(
    val id: String,
    val method: PaymentMethod,
    val status: SdkPaymentStatus,
    val invoice: String?
)

sealed interface SparkPaymentEvent {
    data class PaymentChanged(val payment: SdkPayment) : SparkPaymentEvent
    data object Synced : SparkPaymentEvent
}

fun interface SparkPaymentEventListener {
    suspend fun onEvent(event: SparkPaymentEvent)
}

interface SparkPaymentClient {
    suspend fun parse(input: String): ParsedSdkInput
    suspend fun identityPublicKey(): String
    suspend fun prepare(input: String, amountSats: Satoshi? = null): SdkPreparationResult
    suspend fun prepareLnurl(
        request: ParsedSdkInput.LnurlPay,
        amountSats: Satoshi
    ): SdkPreparationResult
    suspend fun send(prepared: SdkPreparedPayment, idempotencyKey: String): SdkPayment
    suspend fun getPayment(paymentId: String): SdkPayment
    suspend fun listSentPayments(method: PaymentMethod, fromEpochSeconds: Long): List<SdkPayment>
    suspend fun addEventListener(listener: SparkPaymentEventListener): String
    suspend fun removeEventListener(listenerId: String)
}
