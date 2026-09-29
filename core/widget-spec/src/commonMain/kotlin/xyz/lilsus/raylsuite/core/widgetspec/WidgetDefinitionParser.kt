package xyz.lilsus.raylsuite.core.widgetspec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.StoredAmount

sealed interface WidgetDefinitionResult {
    data class Valid(val definition: WidgetDefinition) : WidgetDefinitionResult

    /** Every problem found, so a provider can fix them all at once. */
    data class Invalid(val problems: List<WidgetProblem>) : WidgetDefinitionResult
}

/** [field] is a JSON path such as `purchase.products[1].price`. */
data class WidgetProblem(val field: String, val reason: WidgetProblemReason)

enum class WidgetProblemReason {
    TooLarge,
    MalformedJson,
    UnsupportedSpec,
    UnsupportedPurchase,
    UnsupportedInput,
    UnsupportedCapability,
    Missing,
    WrongType,
    InvalidFormat,
    TextTooLong,
    UnsafeText,
    NotHttps,
    OtherHost,
    UnsupportedCurrency,
    OutOfRange,
    Duplicate,
    Empty,
    Unexpected
}

/**
 * Validates rayl-widget/1 definitions. Unknown fields are ignored;
 * unknown purchase or input types are refused rather than guessed.
 */
object WidgetDefinitionParser {
    const val MAX_BYTES = 64 * 1024
    const val SUPPORTED_SPEC = "rayl-widget/1"

    private const val MAX_TEXT = 2_048
    private const val MAX_ID = 128
    private const val MAX_LABEL = 24
    private val json = Json { isLenient = false }
    private val countryPattern = Regex("""^[A-Z]{2}$""")

    fun parse(sourceUrl: String, body: String): WidgetDefinitionResult {
        val source = WidgetUrl.parse(sourceUrl)
            ?: return invalid("", WidgetProblemReason.NotHttps)
        if (body.encodeToByteArray().size > MAX_BYTES) {
            return invalid("", WidgetProblemReason.TooLarge)
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return invalid("", WidgetProblemReason.MalformedJson)
        return Reader(source).read(root)
    }

    private fun invalid(field: String, reason: WidgetProblemReason) =
        WidgetDefinitionResult.Invalid(listOf(WidgetProblem(field, reason)))

    private class Reader(private val source: WidgetUrl) {
        private val problems = mutableListOf<WidgetProblem>()

        fun read(root: JsonObject): WidgetDefinitionResult {
            val spec = root.string("spec")
            if (spec != null && spec != SUPPORTED_SPEC) {
                problem("spec", WidgetProblemReason.UnsupportedSpec)
            }
            readRequiredCapabilities(root)
            val id = root.id("id")
            val title = root.text("title")
            val providerName = root.obj("provider", required = false)
                ?.text("name", path = "provider.name", required = false)
            val brand = root.obj("brand", required = false)?.widgetBrand()
            val description = root.text("description", required = false)
            val inputs = readInputs(root)
            val purchase = root.obj("purchase", required = false)?.let(::readPurchase)
            val invoiceUrl = root.url("invoiceUrl", sameOrigin = true, required = false)
            val deliveryUrl = root.url("deliveryUrl", sameOrigin = true, required = false)
            val validateUrl = root.url("validateUrl", sameOrigin = true, required = false)
            val statusUrl = root.url("statusUrl", sameOrigin = true, required = false)
            val families = readFamilies(root, purchase)
            val snapshot = readSnapshot(
                root,
                required =
                    statusUrl != null || root["statusUrl"] != null
            )
            val terms = root.obj("terms", required = false)?.let { terms ->
                val url = terms.url("url", sameOrigin = false, path = "terms.url")
                val version = terms.string("version", path = "terms.version")
                if (url != null && version != null) WidgetTerms(url, version) else null
            }

            if (root["purchase"] != null) {
                if (invoiceUrl == null) requireField(root, "invoiceUrl")
                if (deliveryUrl == null) requireField(root, "deliveryUrl")
            } else {
                if (statusUrl == null) requireField(root, "statusUrl")
                if (root["invoiceUrl"] !=
                    null
                ) {
                    problem("invoiceUrl", WidgetProblemReason.Unexpected)
                }
                if (root["deliveryUrl"] !=
                    null
                ) {
                    problem("deliveryUrl", WidgetProblemReason.Unexpected)
                }
            }

            if (problems.isNotEmpty()) return WidgetDefinitionResult.Invalid(problems.toList())
            return WidgetDefinitionResult.Valid(
                WidgetDefinition(
                    source = source,
                    specVersion = spec!!,
                    id = id!!,
                    title = title!!,
                    providerName = providerName,
                    brand = brand,
                    description = description,
                    inputs = inputs,
                    purchase = purchase,
                    invoiceUrl = invoiceUrl,
                    deliveryUrl = deliveryUrl,
                    validateUrl = validateUrl,
                    statusUrl = statusUrl,
                    terms = terms,
                    families = families,
                    snapshot = snapshot
                )
            )
        }

        private fun readPurchase(purchase: JsonObject): WidgetPurchase? =
            when (purchase.string("type", path = "purchase.type")) {
                null -> null

                "product" -> readProducts(purchase)?.let(WidgetPurchase::Product)

                "amount" -> readAmount(purchase)?.let(WidgetPurchase::Amount)

                else -> null.also {
                    problem("purchase.type", WidgetProblemReason.UnsupportedPurchase)
                }
            }

        /**
         * Rayl profile: providers declare supported families and their defaults. Unknown family
         * names are ignored; defaults naming products that do not exist are dropped.
         */
        private fun readFamilies(
            root: JsonObject,
            purchase: WidgetPurchase?
        ): Map<WidgetFamily, WidgetFamilyDefaults> {
            val families = root.obj("families") ?: return emptyMap()
            val productIds = (purchase as? WidgetPurchase.Product)?.products?.map {
                it.id
            }.orEmpty()
            val currency = (purchase as? WidgetPurchase.Amount)?.range?.currencyCode
            val result = WidgetFamily.entries.mapNotNull { family ->
                val body = families[family.wireName] as? JsonObject ?: return@mapNotNull null
                val products = (body["products"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
                    .filter { it in productIds }
                    .distinct()
                val presets = (body["presets"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val raw = (element as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
                    currency?.let { parseWidgetMoney(raw, it) }
                }
                // A label that is unsafe or too long is dropped; the host falls back to a name.
                family to WidgetFamilyDefaults(products, presets, body.safeText("label", MAX_LABEL))
            }.toMap()
            if (result.isEmpty()) problem("families", WidgetProblemReason.Empty)
            return result
        }

        /**
         * Rayl profile: status widgets choose what previews show, so the gallery never fetches
         * live data and never shows a reading without its age.
         */
        private fun readSnapshot(root: JsonObject, required: Boolean): WidgetSnapshot? {
            val snapshot = root.obj("snapshot", required = required) ?: return null
            val status = snapshot.obj("status", path = "snapshot.status") ?: return null
            return status.widgetStatusSample()?.let(::WidgetSnapshot)
                ?: null.also { problem("snapshot.status.value", WidgetProblemReason.Missing) }
        }

        private fun readProducts(purchase: JsonObject): List<WidgetProduct>? {
            val items = purchase.array("products", path = "purchase.products") ?: return null
            val products = items.mapIndexedNotNull { index, element ->
                val path = "purchase.products[$index]"
                val product = element as? JsonObject
                    ?: return@mapIndexedNotNull null.also {
                        problem(path, WidgetProblemReason.WrongType)
                    }
                val id = product.id("id", path = "$path.id")
                val name = product.text("name", path = "$path.name")
                val price = product.obj("price", path = "$path.price")
                    ?.let { readMoney(it, "$path.price") }
                val available = product.boolean("available", path = "$path.available") ?: true
                if (id != null && name != null && price != null) {
                    WidgetProduct(
                        id = id,
                        name = name,
                        price = price,
                        description = product.text(
                            "description",
                            path = "$path.description",
                            required = false
                        ),
                        validity = product.text(
                            "validity",
                            path = "$path.validity",
                            required = false
                        ),
                        available = available
                    )
                } else {
                    null
                }
            }
            if (products.groupBy(WidgetProduct::id).any { it.value.size > 1 }) {
                problem("purchase.products", WidgetProblemReason.Duplicate)
            }
            if (items.isEmpty()) problem("purchase.products", WidgetProblemReason.Empty)
            return products
        }

        private fun readAmount(purchase: JsonObject): WidgetAmountRange? {
            val currency = purchase.string("currency", path = "purchase.currency") ?: return null
            val amount = purchase.obj("amount", path = "purchase.amount") ?: return null
            fun money(field: String): StoredAmount? =
                amount.string(field, path = "purchase.amount.$field")
                    ?.let { moneyOf(it, currency, "purchase.amount.$field") }
            val min = money("min")
            val max = money("max")
            val step = amount["step"]?.let { money("step") }
            val presets = (amount["presets"] as? JsonArray).orEmpty().mapIndexedNotNull {
                    index,
                    it
                ->
                val raw = (it as? JsonPrimitive)?.contentOrNull() ?: return@mapIndexedNotNull null
                moneyOf(raw, currency, "purchase.amount.presets[$index]")
            }
            if (min == null || max == null) return null
            if (min.minor >
                max.minor
            ) {
                problem("purchase.amount.max", WidgetProblemReason.OutOfRange)
            }
            val range = WidgetAmountRange(min, max, step?.minor, presets)
            presets.forEachIndexed { index, preset ->
                if (preset !in range) {
                    problem("purchase.amount.presets[$index]", WidgetProblemReason.OutOfRange)
                }
            }
            return range
        }

        private fun readMoney(price: JsonObject, path: String): StoredAmount? {
            val amount = price.string("amount", path = "$path.amount")
            val currency = price.string("currency", path = "$path.currency")
            if (amount == null || currency == null) return null
            return moneyOf(amount, currency, path)
        }

        private fun moneyOf(amount: String, currency: String, path: String): StoredAmount? {
            if (currency !in CurrencyCatalog.supportedCodes) {
                problem(path, WidgetProblemReason.UnsupportedCurrency)
                return null
            }
            val parsed = parseWidgetMoney(amount, currency)
                ?: return null.also { problem(path, WidgetProblemReason.InvalidFormat) }
            if (parsed.minor <= 0) {
                problem(path, WidgetProblemReason.OutOfRange)
                return null
            }
            return parsed
        }

        /** Semantic extensions must be understood before any input is collected or payment made. */
        private fun readRequiredCapabilities(root: JsonObject) {
            if (root["requires"] == null) return
            val capabilities = root.array("requires") ?: return
            capabilities.forEachIndexed { index, value ->
                val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null) {
                    problem("requires[$index]", WidgetProblemReason.WrongType)
                } else if (!name.matches(Regex("^[a-z][a-z0-9-]{0,63}$"))) {
                    problem("requires[$index]", WidgetProblemReason.InvalidFormat)
                } else {
                    // The initial protocol has no optional semantic capabilities.
                    problem("requires[$index]", WidgetProblemReason.UnsupportedCapability)
                }
            }
        }

        private fun readInputs(root: JsonObject): List<WidgetInput> {
            // Optional: a public status widget asks the user for nothing.
            if (root["inputs"] == null || root["inputs"] is JsonNull) return emptyList()
            val items = root.array("inputs") ?: return emptyList()
            val inputs = items.mapIndexedNotNull { index, element ->
                val path = "inputs[$index]"
                val input = element as? JsonObject
                    ?: return@mapIndexedNotNull null.also {
                        problem(path, WidgetProblemReason.WrongType)
                    }
                val id = input.id("id", path = "$path.id")
                val type = input.string("type", path = "$path.type")?.let { name ->
                    WidgetInputType.entries.firstOrNull { it.wireName == name }
                        ?: null.also {
                            problem("$path.type", WidgetProblemReason.UnsupportedInput)
                        }
                }
                val label = input.text("label", path = "$path.label")
                val required = input.boolean("required", path = "$path.required") ?: true
                val secret = input.boolean("secret", path = "$path.secret") ?: false
                val country = input["country"]?.let { raw ->
                    (raw as? JsonPrimitive)?.contentOrNull()?.takeIf(countryPattern::matches)
                        ?: null.also { problem("$path.country", WidgetProblemReason.InvalidFormat) }
                }
                if (id != null && type != null && label != null) {
                    WidgetInput(
                        id = id,
                        type = type,
                        label = label,
                        required = required,
                        secret = secret,
                        placeholder = input.text(
                            "placeholder",
                            path = "$path.placeholder",
                            required = false
                        ),
                        country = country
                    )
                } else {
                    null
                }
            }
            if (inputs.groupBy(WidgetInput::id).any { it.value.size > 1 }) {
                problem("inputs", WidgetProblemReason.Duplicate)
            }
            return inputs
        }

        private fun requireField(root: JsonObject, field: String) {
            // A present but broken field already reported its own problem.
            if (root[field] == null || root[field] is JsonNull) {
                problem(field, WidgetProblemReason.Missing)
            }
        }

        private fun problem(field: String, reason: WidgetProblemReason) {
            problems += WidgetProblem(field, reason)
        }

        private fun JsonObject.string(
            field: String,
            maxLength: Int = MAX_TEXT,
            required: Boolean = true,
            path: String = field
        ): String? {
            val element = this[field]
            if (element == null || element is JsonNull) {
                if (required) problem(path, WidgetProblemReason.Missing)
                return null
            }
            val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: return null.also { problem(path, WidgetProblemReason.WrongType) }
            if (value.codePointLength() > maxLength) {
                problem(path, WidgetProblemReason.TextTooLong)
                return null
            }
            return value
        }

        private fun JsonObject.text(
            field: String,
            path: String = field,
            required: Boolean = true
        ): String? {
            val value = string(field, required = required, path = path) ?: return null
            if (!value.isSafeWidgetText()) {
                problem(path, WidgetProblemReason.UnsafeText)
                return null
            }
            return value
        }

        private fun JsonObject.id(field: String, path: String = field): String? =
            string(field, maxLength = MAX_ID, path = path)?.let { value ->
                value.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
                    ?: null.also { problem(path, WidgetProblemReason.InvalidFormat) }
            }

        private fun JsonObject.url(
            field: String,
            sameOrigin: Boolean,
            required: Boolean = true,
            path: String = field
        ): WidgetUrl? {
            val raw = string(field, required = required, path = path) ?: return null
            val url = WidgetUrl.parse(raw)
                ?: return null.also { problem(path, WidgetProblemReason.NotHttps) }
            if (sameOrigin && !url.sameOriginAs(source)) {
                problem(path, WidgetProblemReason.OtherHost)
                return null
            }
            return url
        }

        private fun JsonObject.obj(
            field: String,
            required: Boolean = true,
            path: String = field
        ): JsonObject? {
            val element = this[field]
            if (element == null || element is JsonNull) {
                if (required) problem(path, WidgetProblemReason.Missing)
                return null
            }
            return element as? JsonObject
                ?: null.also { problem(path, WidgetProblemReason.WrongType) }
        }

        private fun JsonObject.array(field: String, path: String = field): JsonArray? {
            val element = this[field]
            if (element == null || element is JsonNull) {
                problem(path, WidgetProblemReason.Missing)
                return null
            }
            return element as? JsonArray
                ?: null.also { problem(path, WidgetProblemReason.WrongType) }
        }

        private fun JsonObject.boolean(field: String, path: String): Boolean? {
            val element = this[field] ?: return null
            return (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
                ?: null.also { problem(path, WidgetProblemReason.WrongType) }
        }
    }
}

internal fun JsonPrimitive.contentOrNull(): String? = takeIf(JsonPrimitive::isString)?.content
