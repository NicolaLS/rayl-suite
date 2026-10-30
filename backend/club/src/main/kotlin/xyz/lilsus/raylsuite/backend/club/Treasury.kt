package xyz.lilsus.raylsuite.backend.club

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetPaymentRequest
import breez_sdk_spark.InputType
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.Network
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import com.ionspin.kotlin.bignum.integer.BigInteger
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The Club's own wallet: it issues fee invoices, reports which of them were paid, and pays
 * invoices for members spending their credit.
 */
interface Treasury {
    /** A Bolt11 invoice for [amountSats], valid for [expiry]. */
    suspend fun createInvoice(amountSats: Long, description: String, expiry: Duration): String

    /** Calls [onPaid] with each received invoice, including payments made while offline. */
    suspend fun watchPaidInvoices(onPaid: suspend (invoice: String) -> Unit)

    /**
     * Prices paying [invoice]; [amountMsat] is needed when the invoice has no amount. Throws
     * [TreasuryUnavailable] when the wallet cannot price it now.
     */
    suspend fun quoteSend(invoice: String, amountMsat: Long?): SendQuoteResult

    /**
     * Pays a quote at most once per [idempotencyKey] and waits up to [wait] for the outcome. Never
     * throws for the payment itself: an unclear outcome is [SendOutcome.Pending].
     */
    suspend fun send(quote: SendQuote, idempotencyKey: UUID, wait: Duration): SendOutcome

    /** What became of an earlier send of [invoice]; null when the wallet has no record of it. */
    suspend fun sendOutcome(paymentId: String?, invoice: String): SendOutcome?
}

/** A priced payment; [handle] is the wallet's own preparation. */
class SendQuote(val amountMsat: Long, val feeMsat: Long, val handle: Any? = null)

sealed interface SendQuoteResult {
    data class Ready(val quote: SendQuote) : SendQuoteResult

    /** Not a payable Bolt11 invoice for this network, or the amount doesn't fit it. */
    data object Invalid : SendQuoteResult
    data object Expired : SendQuoteResult
}

sealed interface SendOutcome {
    val paymentId: String?

    data class Settled(val feeMsat: Long, val preimage: String?, override val paymentId: String?) :
        SendOutcome

    /** Definitively not paid. */
    data class Failed(override val paymentId: String?) : SendOutcome

    data class Pending(override val paymentId: String?) : SendOutcome
}

/** Thrown when the treasury cannot issue an invoice now; the Club answers `club_unavailable`. */
class TreasuryUnavailable(cause: Throwable? = null) : Exception("Treasury unavailable", cause)

enum class TreasuryNetwork { Regtest, Mainnet }

data class TreasuryConfig(
    val network: TreasuryNetwork,
    val mnemonic: String,
    /** Required on Mainnet only. */
    val breezApiKey: String?,
    val storageDir: File
) {
    override fun toString() = "TreasuryConfig(network=$network, storageDir=$storageDir)"

    companion object {
        /** Null unless `TREASURY_MNEMONIC` is set; the network follows `CLUB_NETWORK`. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): TreasuryConfig? {
            val mnemonic =
                env["TREASURY_MNEMONIC"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val network = when (val name = env["CLUB_NETWORK"]?.lowercase()) {
                "mainnet" -> TreasuryNetwork.Mainnet
                "regtest", null -> TreasuryNetwork.Regtest
                else -> error("CLUB_NETWORK must be regtest or mainnet, not $name")
            }
            val apiKey = env["BREEZ_API_KEY"]?.takeIf { it.isNotBlank() }
            require(network == TreasuryNetwork.Regtest || apiKey != null) {
                "BREEZ_API_KEY is required on Mainnet"
            }
            return TreasuryConfig(
                network = network,
                mnemonic = mnemonic,
                breezApiKey = apiKey,
                storageDir = File(env["TREASURY_DIR"] ?: "/tmp/rayl-treasury")
            )
        }
    }
}

/** A Spark wallet through the Breez SDK, like the Rayl pocket (D-10). */
class BreezTreasury private constructor(private val sdk: BreezSdk) : Treasury {
    override suspend fun createInvoice(
        amountSats: Long,
        description: String,
        expiry: Duration
    ): String = try {
        sdk.receivePayment(
            ReceivePaymentRequest(
                ReceivePaymentMethod.Bolt11Invoice(
                    description = description,
                    amountSats = amountSats.toULong(),
                    expirySecs = expiry.inWholeSeconds.toUInt(),
                    paymentHash = null,
                    receiverIdentityPublicKey = null
                )
            )
        ).paymentRequest
    } catch (e: Exception) {
        throw TreasuryUnavailable(e)
    }

    override suspend fun watchPaidInvoices(onPaid: suspend (invoice: String) -> Unit) {
        sdk.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    (event as? SdkEvent.PaymentSucceeded)?.payment?.receivedInvoice()?.let {
                        onPaid(it)
                    }
                }
            }
        )
        // Payments that settled while the backend was down: fee quotes live for minutes, so a
        // week of history covers any restart.
        val since = (System.currentTimeMillis() / 1000 - RECONCILE_WINDOW.inWholeSeconds).toULong()
        sdk.listPayments(
            ListPaymentsRequest(
                typeFilter = listOf(PaymentType.RECEIVE),
                statusFilter = listOf(PaymentStatus.COMPLETED),
                fromTimestamp = since
            )
        ).payments.forEach { payment -> payment.receivedInvoice()?.let { onPaid(it) } }
    }

    override suspend fun quoteSend(invoice: String, amountMsat: Long?): SendQuoteResult {
        if (amountMsat != null && (amountMsat <= 0 || amountMsat % MSAT_PER_SAT != 0L)) {
            return SendQuoteResult.Invalid
        }
        val details = try {
            (sdk.parse(invoice) as? InputType.Bolt11Invoice)?.v1
        } catch (e: CancellationException) {
            throw e
        } catch (_: SdkException.InvalidInput) {
            null
        } catch (e: Exception) {
            throw TreasuryUnavailable(e)
        } ?: return SendQuoteResult.Invalid
        val invoiceMsat = details.amountMsat?.toLong()
        if (invoiceMsat != null && amountMsat != null && invoiceMsat != amountMsat) {
            return SendQuoteResult.Invalid
        }
        val msat = invoiceMsat ?: amountMsat ?: return SendQuoteResult.Invalid
        val expiresAt = details.timestamp.toLong() + details.expiry.toLong()
        if (expiresAt <= System.currentTimeMillis() / 1000 + EXPIRY_MARGIN.inWholeSeconds) {
            return SendQuoteResult.Expired
        }
        val prepared = try {
            sdk.prepareSendPayment(
                PrepareSendPaymentRequest(
                    paymentRequest = PaymentRequest.Input(invoice),
                    amount = amountMsat?.let { BigInteger.fromLong(it / MSAT_PER_SAT) },
                    tokenIdentifier = null,
                    conversionOptions = null,
                    feePolicy = null
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SdkException.InvalidInput) {
            return SendQuoteResult.Invalid
        } catch (e: Exception) {
            throw TreasuryUnavailable(e)
        }
        val method = prepared.paymentMethod as? SendPaymentMethod.Bolt11Invoice
            ?: return SendQuoteResult.Invalid
        // A payee that is also on Spark is paid by a Spark transfer, usually without a fee.
        val feeSats = method.sparkTransferFeeSats?.toLong() ?: method.lightningFeeSats.toLong()
        return SendQuoteResult.Ready(
            SendQuote(
                amountMsat = msat,
                feeMsat = feeSats * MSAT_PER_SAT,
                handle = BreezSend(prepared, preferSpark = method.sparkTransferFeeSats != null)
            )
        )
    }

    override suspend fun send(quote: SendQuote, idempotencyKey: UUID, wait: Duration): SendOutcome {
        val prepared = quote.handle as BreezSend
        val deadline = TimeSource.Monotonic.markNow() + wait
        var payment = try {
            sdk.sendPayment(
                SendPaymentRequest(
                    prepareResponse = prepared.response,
                    options = SendPaymentOptions.Bolt11Invoice(
                        preferSpark = prepared.preferSpark,
                        completionTimeoutSecs = wait.inWholeSeconds.toUInt()
                    ),
                    idempotencyKey = idempotencyKey.toString()
                )
            ).payment
        } catch (e: CancellationException) {
            throw e
        } catch (_: SdkException.InsufficientFunds) {
            return SendOutcome.Failed(null)
        } catch (_: Exception) {
            // The SDK may have submitted the payment before failing; the reconciler finds out.
            return SendOutcome.Pending(null)
        }
        while (payment.status == PaymentStatus.PENDING && deadline.hasNotPassedNow()) {
            delay(POLL_INTERVAL)
            payment = runCatching {
                sdk.getPayment(GetPaymentRequest(payment.id)).payment
            }.getOrElse { if (it is CancellationException) throw it else payment }
        }
        return payment.sendOutcome()
    }

    override suspend fun sendOutcome(paymentId: String?, invoice: String): SendOutcome? {
        val payment = if (paymentId != null) {
            sdk.getPayment(GetPaymentRequest(paymentId)).payment
        } else {
            val since = (System.currentTimeMillis() / 1000 - RECONCILE_WINDOW.inWholeSeconds)
            sdk.listPayments(
                ListPaymentsRequest(
                    typeFilter = listOf(PaymentType.SEND),
                    fromTimestamp = since.toULong()
                )
            ).payments.firstOrNull { (it.details as? PaymentDetails.Lightning)?.invoice == invoice }
        }
        return payment?.sendOutcome()
    }

    private fun Payment.sendOutcome(): SendOutcome = when (status) {
        PaymentStatus.COMPLETED -> SendOutcome.Settled(
            feeMsat = fees.longValue() * MSAT_PER_SAT,
            preimage = when (val details = details) {
                is PaymentDetails.Lightning -> details.htlcDetails.preimage
                is PaymentDetails.Spark -> details.htlcDetails?.preimage
                else -> null
            }?.takeIf(String::isNotBlank),
            paymentId = id
        )

        PaymentStatus.FAILED -> SendOutcome.Failed(id)

        PaymentStatus.PENDING -> SendOutcome.Pending(id)
    }

    private class BreezSend(val response: PrepareSendPaymentResponse, val preferSpark: Boolean)

    private fun Payment.receivedInvoice(): String? {
        if (paymentType != PaymentType.RECEIVE || status != PaymentStatus.COMPLETED) return null
        return (details as? PaymentDetails.Lightning)?.invoice
    }

    companion object {
        private val RECONCILE_WINDOW = 7.days
        private val EXPIRY_MARGIN = 10.seconds
        private val POLL_INTERVAL = 1.seconds
        private const val MSAT_PER_SAT = 1_000L

        suspend fun connect(config: TreasuryConfig): BreezTreasury {
            config.storageDir.mkdirs()
            val sdkConfig = defaultConfig(
                when (config.network) {
                    TreasuryNetwork.Regtest -> Network.REGTEST
                    TreasuryNetwork.Mainnet -> Network.MAINNET
                }
            ).also { it.apiKey = config.breezApiKey }
            return BreezTreasury(
                connect(
                    ConnectRequest(
                        config = sdkConfig,
                        seed = Seed.Mnemonic(config.mnemonic, null),
                        storageDir = config.storageDir.absolutePath
                    )
                )
            )
        }
    }
}
