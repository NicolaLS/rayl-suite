package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaymentPreimageTest {
    @Test
    fun lightningAndSparkHtlcPaymentsExposeAvailableProof() {
        val htlc = htlc("ab".repeat(32))
        val lightning = PaymentDetails.Lightning(
            description = null,
            invoice = "invoice",
            destinationPubkey = "recipient",
            htlcDetails = htlc,
            lnurlPayInfo = null,
            lnurlWithdrawInfo = null,
            lnurlReceiveMetadata = null,
            conversionInfo = null
        )
        assertEquals(htlc.preimage, lightning.paymentPreimage())
        assertEquals(htlc.preimage, spark(htlc).paymentPreimage())
    }

    @Test
    fun paymentsWithoutHtlcOrReleasedProofOmitIt() {
        assertNull(spark(null).paymentPreimage())
        assertNull(spark(htlc(null)).paymentPreimage())
        assertNull(spark(htlc(" ")).paymentPreimage())
        assertNull((null as PaymentDetails?).paymentPreimage())
    }

    private fun spark(htlc: SparkHtlcDetails?) = PaymentDetails.Spark(
        invoiceDetails = null,
        htlcDetails = htlc,
        conversionInfo = null
    )

    private fun htlc(preimage: String?) = SparkHtlcDetails(
        paymentHash = "payment-hash",
        preimage = preimage,
        expiryTime = 200uL,
        status = if (preimage == null) {
            SparkHtlcStatus.WAITING_FOR_PREIMAGE
        } else {
            SparkHtlcStatus.PREIMAGE_SHARED
        }
    )
}
