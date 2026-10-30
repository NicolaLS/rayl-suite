package xyz.lilsus.raylsuite.backend.club

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.Network
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** The Club's own wallet: it issues fee invoices and reports which of them were paid. */
interface Treasury {
    /** A Bolt11 invoice for [amountSats], valid for [expiry]. */
    suspend fun createInvoice(amountSats: Long, description: String, expiry: Duration): String

    /** Calls [onPaid] with each received invoice, including payments made while offline. */
    suspend fun watchPaidInvoices(onPaid: suspend (invoice: String) -> Unit)
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

    private fun Payment.receivedInvoice(): String? {
        if (paymentType != PaymentType.RECEIVE || status != PaymentStatus.COMPLETED) return null
        return (details as? PaymentDetails.Lightning)?.invoice
    }

    companion object {
        private val RECONCILE_WINDOW = 7.days

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
