package xyz.lilsus.raylsuite.core.widgetspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.lilsus.raylsuite.core.model.StoredAmount

/** Wire formats of the rayl-widget/1 contract. Transport lives elsewhere. */
object WidgetProtocol {
    const val SPEC = WidgetDefinitionParser.SUPPORTED_SPEC
    internal val json = Json { isLenient = false }
}

/**
 * One purchase attempt. [requestId] identifies the intent: providers should replay their original
 * answer for a repeated id, and a client never pays two invoices for one id.
 */
data class WidgetInvoiceRequest(
    val widgetId: String,
    val requestId: String,
    val productId: String? = null,
    val amount: StoredAmount? = null,
    val inputs: Map<String, String> = emptyMap()
) {
    fun toJson(): String = buildJsonObject {
        put("spec", WidgetProtocol.SPEC)
        put("widgetId", widgetId)
        put("requestId", requestId)
        productId?.let { put("productId", it) }
        amount?.let { putMoney("amount", it) }
        putInputs(inputs)
    }.toString()
}

/** Read-only input check; only sent to an advertised `validateUrl`. */
fun widgetValidateRequestJson(
    widgetId: String,
    inputs: Map<String, String>,
    productId: String? = null
): String = buildJsonObject {
    put("spec", WidgetProtocol.SPEC)
    put("widgetId", widgetId)
    putInputs(inputs)
    productId?.let { put("productId", it) }
}.toString()

/** The v1 error codes. Unknown codes are understood by their HTTP status instead. */
enum class WidgetErrorCode(val wireName: String) {
    InvalidRequest("invalid_request"),
    InvalidInput("invalid_input"),
    OutOfRange("out_of_range"),
    ProductUnavailable("product_unavailable"),
    NotFound("not_found"),
    RateLimited("rate_limited"),
    Unavailable("unavailable");

    companion object {
        internal fun of(name: String?, status: Int): WidgetErrorCode =
            entries.firstOrNull { it.wireName == name } ?: when {
                status == 404 -> NotFound
                status == 429 -> RateLimited
                status >= 500 -> Unavailable
                else -> InvalidRequest
            }
    }
}

sealed interface WidgetInvoiceResult {
    data class Issued(
        val invoice: String,
        val orderId: String,
        /** Absent when the provider's [orderId] is itself an unguessable secret. */
        val orderToken: String?,
        /** What the provider charges: must equal the accepted price or entered amount. */
        val price: StoredAmount,
        val description: String?
    ) : WidgetInvoiceResult

    /** A lookup with nothing to pay; no order exists. */
    data class NothingDue(val message: String?) : WidgetInvoiceResult

    /** The provider refused; [message] is safe to show. */
    data class Refused(val code: WidgetErrorCode, val message: String?, val inputId: String?) :
        WidgetInvoiceResult

    /** Timeouts, unknown errors and malformed answers alike. Stop; never pay. */
    data object NotResponding : WidgetInvoiceResult
}

/** Interprets an invoice endpoint answer. The caller maps transport failures to NotResponding. */
fun parseWidgetInvoiceResponse(status: Int, body: String): WidgetInvoiceResult {
    val root = parseObject(body)
    if (status !in 200..299) {
        // Anything a provider says about a failed request is shown only as its message; the code
        // decides what happens next, and nothing was created that could be paid.
        return WidgetInvoiceResult.Refused(
            code = WidgetErrorCode.of(root?.stringOrNull("error"), status),
            message = root?.safeText("message", MAX_MESSAGE),
            inputId = root?.stringOrNull("inputId")?.takeIf { it.length <= 128 }
        )
    }
    root ?: return WidgetInvoiceResult.NotResponding
    if ((root["payable"] as? JsonPrimitive)?.booleanOrNull == false && root["invoice"] == null) {
        return WidgetInvoiceResult.NothingDue(root.safeText("message", MAX_MESSAGE))
    }
    val invoice = root.stringOrNull("invoice")?.takeIf { it.length in 1..20_000 }
    val orderId = root.stringOrNull("orderId")?.takeIf { it.isNotBlank() && it.length <= 128 }
    val orderToken = root.stringOrNull("orderToken")?.takeIf {
        it.length in 1..256 &&
            it.isPrintable()
    }
    val price = (root["price"] as? JsonObject)?.money()?.takeIf { it.minor > 0 }
    if (invoice == null || orderId == null || price == null) {
        return WidgetInvoiceResult.NotResponding
    }
    return WidgetInvoiceResult.Issued(
        invoice = invoice,
        orderId = orderId,
        orderToken = orderToken,
        price = price,
        description = root.safeText("description", MAX_MESSAGE)
    )
}

sealed interface WidgetValidateResult {
    /** [message], such as a masked account holder, is shown so users know it is the right account. */
    data class Valid(val message: String?) : WidgetValidateResult

    data class Refused(val refusal: WidgetInvoiceResult.Refused) : WidgetValidateResult
}

fun parseWidgetValidateResponse(status: Int, body: String): WidgetValidateResult {
    if (status in 200..299) {
        return WidgetValidateResult.Valid(parseObject(body)?.safeText("message", MAX_MESSAGE))
    }
    val refused = parseWidgetInvoiceResponse(status, body) as? WidgetInvoiceResult.Refused
        ?: WidgetInvoiceResult.Refused(WidgetErrorCode.Unavailable, null, null)
    return WidgetValidateResult.Refused(refused)
}

data class WidgetDeliveryRequest(val orderId: String, val orderToken: String?) {
    fun toJson(): String = buildJsonObject {
        put("spec", WidgetProtocol.SPEC)
        put("orderId", orderId)
        orderToken?.let { put("orderToken", it) }
    }.toString()
}

enum class WidgetDeliveryState(val wireName: String, val terminal: Boolean) {
    Pending("pending", false),
    Processing("processing", false),
    Delivered("delivered", true),
    Failed("failed", true),

    /** Closed without payment. Automatic polling stops; an explicit recheck may still advance it. */
    Expired("expired", true)
}

/**
 * Delivered items are either native (`code`, `pin`, `barcode`, `date`, `text`), which the host
 * draws itself, or hosted (`link`), a provider page the host shows inside the app.
 */
enum class WidgetDeliveryItemKind(val wireName: String) {
    Code("code"),

    /** A secondary credential, shown hidden until the user asks for it. */
    Pin("pin"),

    /** A scannable code; always comes with a [WidgetBarcodeFormat]. */
    Barcode("barcode"),
    Text("text"),
    Date("date"),

    /** An HTTPS page, such as a voucher, that the host shows inside the app. */
    Link("link")
}

/** The barcode formats Apple Wallet passes support, so a voucher can later become a pass. */
enum class WidgetBarcodeFormat(val wireName: String) {
    Qr("qr"),
    Code128("code128"),
    Pdf417("pdf417"),
    Aztec("aztec")
}

/**
 * How a delivery is shown. A receipt confirms something done (a top-up, a bill); a voucher is
 * something the user keeps and redeems, drawn as a card in the product's brand.
 */
enum class WidgetDeliveryPresentation(val wireName: String) {
    Receipt("receipt"),
    Voucher("voucher")
}

data class WidgetDeliveryItem(
    val kind: WidgetDeliveryItemKind,
    val label: String,
    val value: String,
    /** Set for [WidgetDeliveryItemKind.Barcode] only. */
    val format: WidgetBarcodeFormat? = null
) {
    /**
     * Pages the host shows inside the app: `link` items, and text that is nothing but a strict
     * HTTPS URL, as older deliveries sent redemption pages that way.
     */
    val opensInApp: Boolean
        get() = (kind == WidgetDeliveryItemKind.Link || kind == WidgetDeliveryItemKind.Text) &&
            WidgetUrl.parse(value) != null
}

sealed interface WidgetDeliveryResult {
    data class Report(
        val state: WidgetDeliveryState,
        val message: String?,
        val items: List<WidgetDeliveryItem>,
        val retryAfterSeconds: Int?,
        val presentation: WidgetDeliveryPresentation = WidgetDeliveryPresentation.Receipt
    ) : WidgetDeliveryResult

    /** The provider does not recognize the order and token. */
    data object NotFound : WidgetDeliveryResult

    data object NotResponding : WidgetDeliveryResult
}

fun parseWidgetDeliveryResponse(status: Int, body: String): WidgetDeliveryResult {
    if (status == 404) return WidgetDeliveryResult.NotFound
    if (status !in 200..299) return WidgetDeliveryResult.NotResponding
    val root = parseObject(body) ?: return WidgetDeliveryResult.NotResponding
    val state = root.stringOrNull("state")?.let { name ->
        WidgetDeliveryState.entries.firstOrNull { it.wireName == name }
    } ?: return WidgetDeliveryResult.NotResponding
    val items = (root["items"] as? JsonArray).orEmpty().take(MAX_DELIVERY_ITEMS).mapNotNull {
        val item = it as? JsonObject ?: return@mapNotNull null
        val label = item.safeText("label", 128) ?: return@mapNotNull null
        val value = item.safeText("value", MAX_MESSAGE) ?: return@mapNotNull null
        val kind = item.stringOrNull("kind")?.let { name ->
            WidgetDeliveryItemKind.entries.firstOrNull { kind -> kind.wireName == name }
        } ?: WidgetDeliveryItemKind.Text
        // Only a strict HTTPS URL may be opened in the app; anything else stays plain text.
        val format = item.stringOrNull("format")?.let { name ->
            WidgetBarcodeFormat.entries.firstOrNull { it.wireName == name }
        }
        val safeKind = when {
            kind == WidgetDeliveryItemKind.Link && WidgetUrl.parse(
                value
            ) == null -> WidgetDeliveryItemKind.Text

            // A barcode the host cannot draw is still a usable code.
            kind == WidgetDeliveryItemKind.Barcode && format == null -> WidgetDeliveryItemKind.Code

            else -> kind
        }
        WidgetDeliveryItem(
            safeKind,
            label,
            value,
            format.takeIf {
                safeKind ==
                    WidgetDeliveryItemKind.Barcode
            }
        )
    }
    return WidgetDeliveryResult.Report(
        state = state,
        message = root.safeText("message", MAX_MESSAGE),
        items = items,
        retryAfterSeconds = (root["retryAfterSeconds"] as? JsonPrimitive)?.intOrNull
            ?.coerceIn(1, 3_600),
        presentation = root.stringOrNull("presentation")?.let { name ->
            WidgetDeliveryPresentation.entries.firstOrNull { it.wireName == name }
        } ?: WidgetDeliveryPresentation.Receipt
    )
}

/** An understood typed status value, for locale-aware display. */
sealed interface WidgetTypedValue {
    data class Money(val amount: String, val currencyCode: String) : WidgetTypedValue

    data class Number(val value: String, val unit: String?) : WidgetTypedValue

    data class Timestamp(val value: String) : WidgetTypedValue
}

data class WidgetStatusValue(
    val title: String?,
    /** The provider's display fallback. */
    val value: String,
    val typedValue: WidgetTypedValue?,
    val subtitle: String?,
    val asOf: String,
    val observedAt: String,
    val refreshAfterSeconds: Int,
    val trend: WidgetTrend? = null
)

fun widgetStatusRequestJson(widgetId: String, inputs: Map<String, String>): String =
    buildJsonObject {
        put("spec", WidgetProtocol.SPEC)
        put("widgetId", widgetId)
        putInputs(inputs)
    }.toString()

/** Null when the answer is unusable; callers keep showing the previous value with its age. */
fun parseWidgetStatusResponse(status: Int, body: String): WidgetStatusValue? {
    if (status !in 200..299) return null
    val root = parseObject(body) ?: return null
    val asOf = root.stringOrNull("asOf")?.takeIf { it.isTimestamp() } ?: return null
    return WidgetStatusValue(
        title = root.safeText("title", MAX_MESSAGE),
        value = root.safeText("value", MAX_MESSAGE) ?: return null,
        typedValue = (root["typedValue"] as? JsonObject)?.typedValue(),
        subtitle = root.safeText("subtitle", MAX_MESSAGE),
        asOf = asOf,
        observedAt = root.stringOrNull("observedAt")?.takeIf { it.isTimestamp() } ?: asOf,
        refreshAfterSeconds = (root["refreshAfterSeconds"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 } ?: DEFAULT_REFRESH_SECONDS,
        trend = (root["trend"] as? JsonObject)?.widgetTrend()
    )
}

/** Null without a usable value; unsafe or overlong optional text is dropped. */
internal fun JsonObject.widgetStatusSample(): WidgetStatusSample? = WidgetStatusSample(
    title = safeText("title", MAX_MESSAGE),
    value = safeText("value", MAX_MESSAGE) ?: return null,
    typedValue = (this["typedValue"] as? JsonObject)?.typedValue(),
    subtitle = safeText("subtitle", MAX_MESSAGE),
    trend = (this["trend"] as? JsonObject)?.widgetTrend()
)

/** A trend needs 2 to 96 finite numbers; anything else is dropped rather than half drawn. */
internal fun JsonObject.widgetTrend(): WidgetTrend? {
    val raw = (this["points"] as? JsonArray) ?: return null
    if (raw.size !in 2..MAX_TREND_POINTS) return null
    val points = raw.map { element ->
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: return null
    }
    return WidgetTrend(points, safeText("period", MAX_PERIOD))
}

/** A brand: a name, a PNG data URL of at most 32 KiB, and a `#RRGGBB` tint. */
internal fun JsonObject.widgetBrand(): WidgetBrand? {
    val icon = stringOrNull("icon")?.takeIf { it.startsWith(PNG_DATA_URL) }
        ?.removePrefix(PNG_DATA_URL)
        ?.takeIf { it.length <= MAX_ICON_BASE64 && it.isPng() }
    val tint = stringOrNull("tint")?.takeIf { TINT.matches(it) }?.uppercase()
    val name = safeText("name", 128)
    return if (icon == null && tint == null &&
        name == null
    ) {
        null
    } else {
        WidgetBrand(name = name, iconPng = icon, tint = tint)
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun String.isPng(): Boolean {
    val bytes = runCatching { kotlin.io.encoding.Base64.decode(this) }.getOrNull() ?: return false
    return bytes.size in 8..MAX_ICON_BYTES && bytes.take(8) == PNG_SIGNATURE
}

private const val PNG_DATA_URL = "data:image/png;base64,"
private const val MAX_ICON_BYTES = 32 * 1_024
private const val MAX_ICON_BASE64 = (MAX_ICON_BYTES + 2) / 3 * 4
private const val MAX_TREND_POINTS = 96
private const val MAX_PERIOD = 16
private val TINT = Regex("^#[0-9A-Fa-f]{6}$")
private val PNG_SIGNATURE = listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)

private const val MAX_DELIVERY_ITEMS = 20
private const val MAX_MESSAGE = 2_048
private const val DEFAULT_REFRESH_SECONDS = 900

private fun JsonObjectBuilder.putMoney(field: String, amount: StoredAmount) {
    putJsonObject(field) {
        put("amount", amount.toWidgetMoneyString())
        put("currency", amount.normalizedCurrencyCode)
    }
}

private fun JsonObjectBuilder.putInputs(inputs: Map<String, String>) {
    putJsonObject("inputs") { inputs.forEach { (key, value) -> put(key, value) } }
}

private fun JsonObject.money(): StoredAmount? {
    val amount = stringOrNull("amount") ?: return null
    val currency = stringOrNull("currency") ?: return null
    return parseWidgetMoney(amount, currency)
}

private fun JsonObject.typedValue(): WidgetTypedValue? = when (stringOrNull("type")) {
    "money" -> {
        val amount = stringOrNull("amount")
        val currency = stringOrNull("currency")
        if (amount != null && currency != null && amount.isDecimal()) {
            WidgetTypedValue.Money(amount, currency)
        } else {
            null
        }
    }

    "number" -> stringOrNull("value")?.takeIf { it.isDecimal() }
        ?.let { WidgetTypedValue.Number(it, safeText("unit", 32)) }

    "timestamp" -> stringOrNull("value")?.takeIf { it.isTimestamp() }
        ?.let(WidgetTypedValue::Timestamp)

    else -> null
}

private fun parseObject(body: String): JsonObject? =
    runCatching { WidgetProtocol.json.parseToJsonElement(body) }.getOrNull() as? JsonObject

private fun JsonObject.stringOrNull(field: String): String? =
    (this[field] as? JsonPrimitive)?.contentOrNull()

/** Provider text that is unsafe or too long is dropped rather than shown. */
internal fun JsonObject.safeText(field: String, maxLength: Int): String? = stringOrNull(field)
    ?.takeIf { it.isSafeWidgetText() && it.codePointLength() <= maxLength }

private fun String.isUrlSafe(lengths: IntRange): Boolean =
    length in lengths && all { (it.isLetterOrDigit() && it.code < 128) || it == '-' || it == '_' }

private fun String.isDecimal(): Boolean = Regex("""^-?[0-9]{1,30}(\.[0-9]{1,18})?$""").matches(this)

private fun String.isTimestamp(): Boolean =
    Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]{8,15}Z$""").matches(this)

private fun String.isPrintable(): Boolean = all { it.code in 0x21..0x7E }
