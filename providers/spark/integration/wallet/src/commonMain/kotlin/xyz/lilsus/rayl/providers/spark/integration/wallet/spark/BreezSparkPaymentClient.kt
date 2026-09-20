package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.BitcoinNetwork
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetPaymentRequest
import breez_sdk_spark.InputType
import breez_sdk_spark.LnurlPayRequest
import breez_sdk_spark.LnurlPayRequestDetails
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PrepareLnurlPayRequest
import breez_sdk_spark.PrepareLnurlPayResponse
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import com.ionspin.kotlin.bignum.integer.BigInteger
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.payment.Bolt11Invoice
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.lilsus.rayl.providers.spark.application.payment.InvoiceFingerprint
import xyz.lilsus.rayl.providers.spark.application.payment.LnurlPayRequestPayload
import xyz.lilsus.rayl.providers.spark.application.payment.LnurlPayReviewDetails
import xyz.lilsus.rayl.providers.spark.application.payment.ParsedSdkInput
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentMethod
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentNetwork
import xyz.lilsus.rayl.providers.spark.application.payment.PreparedSdkPayload
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPayment
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPaymentStatus
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPreparationResult
import xyz.lilsus.rayl.providers.spark.application.payment.SdkPreparedPayment
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.raylsuite.core.model.Satoshi
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
import xyz.lilsus.raylsuite.core.payment.PaymentInputParseAttempt
import xyz.lilsus.raylsuite.core.payment.PaymentInputParser

class BreezSparkPaymentClient(private val sdk: BreezSdk) : SparkPaymentClient {
    private val inputParser =
        LightningInputParser(
            additionalInstantParsers = listOf(PaymentInputParser(::parseSparkInput))
        )

    override suspend fun parse(input: String): ParsedSdkInput = try {
        when (val result = inputParser.parse(input)) {
            is LightningInputParser.ParseResult.Success ->
                when (val target = result.target) {
                    is LightningInputParser.Target.Bolt11 ->
                        if (input.startsWith("bitcoin:", ignoreCase = true)) {
                            normalizeBreezInput(sdk.parse(input))
                        } else {
                            target.invoice.toParsedSdkInput()
                        }

                    is SparkInputTarget -> target.input

                    is LightningInputParser.Target.Lnurl,
                    is LightningInputParser.Target.LightningAddressTarget ->
                        normalizeBreezInput(sdk.parse(input))

                    else -> ParsedSdkInput.Unsupported
                }

            is LightningInputParser.ParseResult.Failure ->
                when (result.reason) {
                    LightningInputParser.FailureReason.BitcoinAddress -> ParsedSdkInput.OnChain
                    else -> ParsedSdkInput.Unsupported
                }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SdkException) {
        ParsedSdkInput.Unsupported
    }

    private suspend fun parseSparkInput(
        input: String
    ): PaymentInputParseAttempt<
        LightningInputParser.Target,
        LightningInputParser.FailureReason
        > {
        if (!SPARK_PREFIXES.any { input.startsWith(it, ignoreCase = true) }) {
            return PaymentInputParseAttempt.NoMatch
        }
        return when (val parsed = normalizeBreezInput(sdk.parse(input))) {
            is ParsedSdkInput.SparkInvoice ->
                PaymentInputParseAttempt.Parsed(SparkInputTarget(parsed))

            else -> PaymentInputParseAttempt.Rejected(
                LightningInputParser.FailureReason.Unrecognized
            )
        }
    }

    override suspend fun identityPublicKey(): String =
        sdk.getInfo(GetInfoRequest(ensureSynced = false)).identityPubkey

    override suspend fun prepare(input: String, amountSats: Satoshi?): SdkPreparationResult =
        preparationResult {
            val response = sdk.prepareSendPayment(
                PrepareSendPaymentRequest(
                    paymentRequest = PaymentRequest.Input(input),
                    amount = amountSats?.value?.let(BigInteger::fromLong),
                    tokenIdentifier = null,
                    conversionOptions = null,
                    feePolicy = null
                )
            )
            val methodAndFee = when (val method = response.paymentMethod) {
                is SendPaymentMethod.Bolt11Invoice ->
                    PaymentMethod.BOLT11 to
                        method.lightningFeeSats.toLong()

                is SendPaymentMethod.SparkInvoice ->
                    PaymentMethod.SPARK_INVOICE to
                        method.fee.longValue()

                else -> null to 0L
            }
            SdkPreparedPayment(
                payload = BreezPreparedPayload(response),
                method = methodAndFee.first ?: PaymentMethod.BOLT11,
                amountSats = Satoshi.positive(
                    runCatching {
                        response.amount.longValue()
                    }.getOrDefault(-1)
                ),
                feeSats = Satoshi.nonNegative(methodAndFee.second),
                tokenIdentifier = response.tokenIdentifier,
                hasConversion = response.conversionEstimate != null
            )
        }

    override suspend fun prepareLnurl(
        request: ParsedSdkInput.LnurlPay,
        amountSats: Satoshi
    ): SdkPreparationResult = preparationResult {
        val details = (request.payload as? BreezLnurlRequestPayload)?.details
            ?: error("LNURL request did not originate from Breez")
        val response = sdk.prepareLnurlPay(
            PrepareLnurlPayRequest(
                amount = BigInteger.fromLong(amountSats.value),
                payRequest = details,
                comment = null,
                validateSuccessActionUrl = true,
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = null
            )
        )
        val invoice = response.invoiceDetails
        val expectedMsat = amountSats.value.toULong() * 1_000uL
        require(
            response.amountSats == amountSats.value.toULong() && invoice.amountMsat == expectedMsat
        ) {
            "Breez returned an LNURL invoice for a different amount"
        }
        SdkPreparedPayment(
            payload = BreezLnurlPreparedPayload(response),
            method = PaymentMethod.BOLT11,
            amountSats = Satoshi.positive(response.amountSats.checkedLong("LNURL amount")),
            feeSats = Satoshi.nonNegative(response.feeSats.checkedLong("LNURL fee")),
            tokenIdentifier = null,
            hasConversion = response.conversionEstimate != null,
            resolvedInvoiceFingerprint = InvoiceFingerprint.bolt11(invoice.paymentHash),
            resolvedNetwork = invoice.network.toPaymentNetwork(),
            resolvedExpiresAtEpochSeconds = invoice.timestamp.saturatingAdd(invoice.expiry)
        )
    }

    override suspend fun send(prepared: SdkPreparedPayment, idempotencyKey: String): SdkPayment {
        val lnurlPayload = prepared.payload as? BreezLnurlPreparedPayload
        if (lnurlPayload != null) {
            return sdk.lnurlPay(
                LnurlPayRequest(
                    prepareResponse = lnurlPayload.response,
                    idempotencyKey = idempotencyKey
                )
            ).payment.toDomain()
        }
        val response = (prepared.payload as? BreezPreparedPayload)?.response
            ?: error("Prepared payment did not originate from Breez")
        val options = when (prepared.method) {
            PaymentMethod.BOLT11 -> SendPaymentOptions.Bolt11Invoice(
                preferSpark = false,
                completionTimeoutSecs = null
            )

            PaymentMethod.SPARK_INVOICE -> null
        }
        return sdk.sendPayment(
            SendPaymentRequest(
                prepareResponse = response,
                options = options,
                idempotencyKey = idempotencyKey
            )
        ).payment.toDomain()
    }

    override suspend fun getPayment(paymentId: String): SdkPayment =
        sdk.getPayment(GetPaymentRequest(paymentId)).payment.toDomain()

    override suspend fun observePayments(onPayment: (SdkPayment) -> Unit): suspend () -> Unit {
        val listenerId = sdk.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    when (event) {
                        is SdkEvent.PaymentSucceeded -> onPayment(event.payment.toDomain())
                        is SdkEvent.PaymentFailed -> onPayment(event.payment.toDomain())
                        else -> Unit
                    }
                }
            }
        )
        return { sdk.removeEventListener(listenerId) }
    }

    private suspend fun preparationResult(
        block: suspend () -> SdkPreparedPayment
    ): SdkPreparationResult = try {
        SdkPreparationResult.Prepared(block())
    } catch (_: SdkException.InsufficientFunds) {
        SdkPreparationResult.InsufficientFunds
    }

    private fun Payment.toDomain(): SdkPayment = SdkPayment(
        id = id,
        status = when (status) {
            PaymentStatus.PENDING -> SdkPaymentStatus.PENDING
            PaymentStatus.COMPLETED -> SdkPaymentStatus.COMPLETED
            PaymentStatus.FAILED -> SdkPaymentStatus.FAILED
        }
    )

    private fun ULong.checkedLong(label: String): Long {
        require(this <= Long.MAX_VALUE.toULong()) { "$label exceeds supported range" }
        return toLong()
    }

    private data class BreezPreparedPayload(val response: PrepareSendPaymentResponse) :
        PreparedSdkPayload

    private class BreezLnurlPreparedPayload(val response: PrepareLnurlPayResponse) :
        PreparedSdkPayload {
        override fun toString(): String = "BreezLnurlPreparedPayload(<redacted>)"
    }

    companion object {
        private val SPARK_PREFIXES =
            listOf("spark1", "sparkt1", "sparkrt1", "sparks1", "sp1", "spt1", "sprt1", "sps1")
    }
}

private data class SparkInputTarget(val input: ParsedSdkInput.SparkInvoice) :
    LightningInputParser.Target

private fun Bolt11Invoice.toParsedSdkInput(): ParsedSdkInput.Bolt11 {
    val timestamp = timestampSeconds.coerceAtLeast(0).toULong()
    val expiry =
        (expirySeconds ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS.toLong()).coerceAtLeast(0).toULong()
    return ParsedSdkInput.Bolt11(
        invoice = write(),
        paymentHash = paymentHash.toHex(),
        amountMsat = amount?.msat?.takeIf { it > 0 }?.toULong(),
        network =
            when (chain) {
                Chain.Mainnet -> PaymentNetwork.MAINNET
                Chain.Regtest -> PaymentNetwork.REGTEST
                else -> PaymentNetwork.OTHER
            },
        expiresAtEpochSeconds = timestamp.saturatingAdd(expiry)
    )
}

fun normalizeBreezInput(input: InputType): ParsedSdkInput =
    normalizeBreezInput(input, allowBip21 = true)

private fun normalizeBreezInput(input: InputType, allowBip21: Boolean): ParsedSdkInput =
    when (input) {
        is InputType.Bolt11Invoice -> ParsedSdkInput.Bolt11(
            invoice = input.v1.invoice.bolt11,
            paymentHash = input.v1.paymentHash,
            amountMsat = input.v1.amountMsat,
            network = input.v1.network.toPaymentNetwork(),
            expiresAtEpochSeconds = input.v1.timestamp.saturatingAdd(input.v1.expiry)
        )

        is InputType.SparkInvoice -> ParsedSdkInput.SparkInvoice(
            invoice = input.v1.invoice,
            amountSats = input.v1.amount?.let {
                runCatching { it.longValue() }.getOrDefault(Long.MIN_VALUE)
            },
            network = input.v1.network.toPaymentNetwork(),
            expiryTimeEpochSeconds = input.v1.expiryTime,
            tokenIdentifier = input.v1.tokenIdentifier,
            senderPublicKey = input.v1.senderPublicKey
        )

        is InputType.LightningAddress -> ParsedSdkInput.LnurlPay(
            requestFingerprint = InvoiceFingerprint.lnurl(input.v1.address.lowercase()),
            minSendableMsat = input.v1.payRequest.minSendable,
            maxSendableMsat = input.v1.payRequest.maxSendable,
            reviewDetails = input.v1.payRequest.toReviewDetails(),
            payload = BreezLnurlRequestPayload(input.v1.payRequest)
        )

        is InputType.LnurlPay -> ParsedSdkInput.LnurlPay(
            requestFingerprint = InvoiceFingerprint.lnurl(
                input.v1.address?.lowercase() ?: input.v1.url
            ),
            minSendableMsat = input.v1.minSendable,
            maxSendableMsat = input.v1.maxSendable,
            reviewDetails = input.v1.toReviewDetails(),
            payload = BreezLnurlRequestPayload(input.v1)
        )

        is InputType.Bip21 -> normalizeBip21(input, allowBip21)

        is InputType.BitcoinAddress -> ParsedSdkInput.OnChain

        else -> ParsedSdkInput.Unsupported
    }

private fun normalizeBip21(input: InputType.Bip21, allowBip21: Boolean): ParsedSdkInput {
    if (!allowBip21 || input.v1.assetId != null) return ParsedSdkInput.Unsupported
    if (input.v1.paymentMethods.isEmpty()) return ParsedSdkInput.OnChain
    val normalized = input.v1.paymentMethods.map { normalizeBreezInput(it, allowBip21 = false) }
    val instant = normalized.filter {
        it is ParsedSdkInput.Bolt11 || it is ParsedSdkInput.SparkInvoice ||
            it is ParsedSdkInput.LnurlPay
    }
    if (instant.isEmpty()) {
        return if (normalized.all { it === ParsedSdkInput.OnChain }) {
            ParsedSdkInput.OnChain
        } else {
            ParsedSdkInput.Unsupported
        }
    }
    val selected = instant.singleOrNull() ?: return ParsedSdkInput.Unsupported
    return applyBip21Amount(input.v1.amountSat, selected)
}

private fun applyBip21Amount(amountSats: ULong?, input: ParsedSdkInput): ParsedSdkInput {
    val outerAmount = amountSats ?: return input
    if (outerAmount == 0uL ||
        outerAmount > Long.MAX_VALUE.toULong()
    ) {
        return ParsedSdkInput.Unsupported
    }
    val amount = outerAmount.toLong()
    return when (input) {
        is ParsedSdkInput.Bolt11 -> when (val invoiceAmount = input.amountMsat) {
            null -> if (outerAmount <= ULong.MAX_VALUE / 1_000uL) {
                input.copy(amountMsat = outerAmount * 1_000uL, amountOverrideSats = amount)
            } else {
                ParsedSdkInput.Unsupported
            }

            else -> if (invoiceAmount % 1_000uL == 0uL && invoiceAmount / 1_000uL == outerAmount) {
                input
            } else {
                ParsedSdkInput.Unsupported
            }
        }

        is ParsedSdkInput.SparkInvoice -> when (val invoiceAmount = input.amountSats) {
            null -> input.copy(amountSats = amount, amountOverrideSats = amount)
            else -> if (invoiceAmount == amount) input else ParsedSdkInput.Unsupported
        }

        is ParsedSdkInput.LnurlPay -> ParsedSdkInput.LnurlPay(
            requestFingerprint = input.requestFingerprint,
            minSendableMsat = input.minSendableMsat,
            maxSendableMsat = input.maxSendableMsat,
            reviewDetails = input.reviewDetails,
            payload = input.payload,
            amountOverrideSats = amount
        )

        ParsedSdkInput.OnChain, ParsedSdkInput.Unsupported -> ParsedSdkInput.Unsupported
    }
}

private fun BitcoinNetwork.toPaymentNetwork(): PaymentNetwork = when (this) {
    BitcoinNetwork.BITCOIN -> PaymentNetwork.MAINNET
    BitcoinNetwork.REGTEST -> PaymentNetwork.REGTEST
    else -> PaymentNetwork.OTHER
}

private fun ULong.saturatingAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

private class BreezLnurlRequestPayload(val details: LnurlPayRequestDetails) :
    LnurlPayRequestPayload {
    override fun toString(): String = "BreezLnurlRequestPayload(<redacted>)"
}

private fun LnurlPayRequestDetails.toReviewDetails(): LnurlPayReviewDetails {
    var description: String? = null
    var imagePng: String? = null
    var imageJpeg: String? = null
    val metadata = runCatching { Json.parseToJsonElement(metadataStr) }.getOrNull() as? JsonArray
    metadata?.forEach { entry ->
        val values = entry as? JsonArray ?: return@forEach
        val type = (values.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return@forEach
        val value = (values.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        when (type.lowercase()) {
            "text/plain" -> description = value
            "image/png;base64" -> imagePng = value
            "image/jpeg;base64" -> imageJpeg = value
        }
    }
    return LnurlPayReviewDetails(
        domain = domain,
        description = description,
        imagePngBase64 = imagePng,
        imageJpegBase64 = imageJpeg
    )
}
