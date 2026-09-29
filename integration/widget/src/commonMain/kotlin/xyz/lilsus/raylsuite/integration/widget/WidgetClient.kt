package xyz.lilsus.raylsuite.integration.widget

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.lilsus.raylsuite.core.network.createWidgetHttpClient
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinition
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionParser
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDefinitionResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryRequest
import xyz.lilsus.raylsuite.core.widgetspec.WidgetDeliveryResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetErrorCode
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInvoiceRequest
import xyz.lilsus.raylsuite.core.widgetspec.WidgetInvoiceResult
import xyz.lilsus.raylsuite.core.widgetspec.WidgetProblem
import xyz.lilsus.raylsuite.core.widgetspec.WidgetStatusValue
import xyz.lilsus.raylsuite.core.widgetspec.WidgetUrl
import xyz.lilsus.raylsuite.core.widgetspec.WidgetValidateResult
import xyz.lilsus.raylsuite.core.widgetspec.parseWidgetDeliveryResponse
import xyz.lilsus.raylsuite.core.widgetspec.parseWidgetInvoiceResponse
import xyz.lilsus.raylsuite.core.widgetspec.parseWidgetStatusResponse
import xyz.lilsus.raylsuite.core.widgetspec.parseWidgetValidateResponse
import xyz.lilsus.raylsuite.core.widgetspec.widgetStatusRequestJson
import xyz.lilsus.raylsuite.core.widgetspec.widgetValidateRequestJson

sealed interface WidgetDefinitionFetch {
    /** [body] is kept so the client can re-read a snapshot offline. */
    data class Loaded(val definition: WidgetDefinition, val body: String, val etag: String?) :
        WidgetDefinitionFetch

    /** The cached copy identified by the sent validator is still current. */
    data object NotModified : WidgetDefinitionFetch

    data class Invalid(val problems: List<WidgetProblem>) : WidgetDefinitionFetch

    /** 404 or 410: the provider withdrew the widget. */
    data object Gone : WidgetDefinitionFetch

    data object NotResponding : WidgetDefinitionFetch
}

/** HTTP transport for rayl-widget providers. Requests carry no cookies or identifying headers. */
class WidgetClient(private val http: HttpClient = createWidgetHttpClient()) {
    suspend fun fetchDefinition(url: String, etag: String? = null): WidgetDefinitionFetch {
        if (WidgetUrl.parse(url) == null) {
            return when (val result = WidgetDefinitionParser.parse(url, "")) {
                is WidgetDefinitionResult.Invalid -> WidgetDefinitionFetch.Invalid(result.problems)
                is WidgetDefinitionResult.Valid -> WidgetDefinitionFetch.NotResponding
            }
        }
        val response = call {
            http.get(url) {
                header(HttpHeaders.Accept, "application/json")
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
        } ?: return WidgetDefinitionFetch.NotResponding
        return when (response.status.value) {
            304 -> WidgetDefinitionFetch.NotModified

            404, 410 -> WidgetDefinitionFetch.Gone

            in 200..299 -> {
                val body = response.boundedText(WidgetDefinitionParser.MAX_BYTES)
                    ?: return WidgetDefinitionFetch.Invalid(
                        WidgetDefinitionParser.parse(url, "x".repeat(MAX + 1)).problems()
                    )
                when (val result = WidgetDefinitionParser.parse(url, body)) {
                    is WidgetDefinitionResult.Valid -> WidgetDefinitionFetch.Loaded(
                        result.definition,
                        body,
                        response.headers[HttpHeaders.ETag]
                    )

                    is WidgetDefinitionResult.Invalid ->
                        WidgetDefinitionFetch.Invalid(result.problems)
                }
            }

            else -> WidgetDefinitionFetch.NotResponding
        }
    }

    suspend fun requestInvoice(
        definition: WidgetDefinition,
        request: WidgetInvoiceRequest
    ): WidgetInvoiceResult {
        val url = definition.invoiceUrl ?: return WidgetInvoiceResult.NotResponding
        val response = post(url, request.toJson()) ?: return WidgetInvoiceResult.NotResponding
        val body = response.boundedText(RESPONSE_BYTES) ?: return WidgetInvoiceResult.NotResponding
        return parseWidgetInvoiceResponse(response.status.value, body)
    }

    /** Read-only input check. Without a `validateUrl` the inputs are accepted as they are. */
    suspend fun validate(
        definition: WidgetDefinition,
        inputs: Map<String, String>,
        productId: String? = null
    ): WidgetValidateResult {
        val url = definition.validateUrl ?: return WidgetValidateResult.Valid(null)
        val response = post(url, widgetValidateRequestJson(definition.id, inputs, productId))
            ?: return WidgetValidateResult.Refused(
                WidgetInvoiceResult.Refused(WidgetErrorCode.Unavailable, null, null)
            )
        val body = response.boundedText(RESPONSE_BYTES) ?: ""
        return parseWidgetValidateResponse(response.status.value, body)
    }

    suspend fun requestDelivery(
        deliveryUrl: String,
        request: WidgetDeliveryRequest
    ): WidgetDeliveryResult {
        val url = WidgetUrl.parse(deliveryUrl) ?: return WidgetDeliveryResult.NotResponding
        val response = post(url, request.toJson()) ?: return WidgetDeliveryResult.NotResponding
        val body = response.boundedText(RESPONSE_BYTES) ?: return WidgetDeliveryResult.NotResponding
        return parseWidgetDeliveryResponse(response.status.value, body)
    }

    suspend fun fetchStatus(
        definition: WidgetDefinition,
        inputs: Map<String, String>
    ): WidgetStatusValue? {
        val url = definition.statusUrl ?: return null
        val response = post(url, widgetStatusRequestJson(definition.id, inputs)) ?: return null
        val body = response.boundedText(RESPONSE_BYTES) ?: return null
        return parseWidgetStatusResponse(response.status.value, body)
    }

    private suspend fun post(url: WidgetUrl, json: String): HttpResponse? = call {
        http.post(url.value) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json")
            setBody(json)
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse? = try {
        block()
    } catch (_: CancellationException) {
        // Only a cancelled caller stops here; an engine's stray cancellation is a failed call,
        // and must not end loops such as the status refresh.
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Throwable) {
        null
    }

    /** Reads at most [limit] bytes; a longer body is refused rather than truncated. */
    private suspend fun HttpResponse.boundedText(limit: Int): String? = try {
        val channel = bodyAsChannel()
        val buffer = ByteArray(limit + 1)
        var read = 0
        while (read <= limit) {
            val count = channel.readAvailable(buffer, read, buffer.size - read)
            if (count == -1) break
            read += count
        }
        if (read > limit) null else buffer.decodeToString(0, read)
    } catch (_: CancellationException) {
        // Only a cancelled caller stops here; an engine's stray cancellation is a failed call,
        // and must not end loops such as the status refresh.
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Throwable) {
        null
    }

    private fun WidgetDefinitionResult.problems(): List<WidgetProblem> =
        (this as? WidgetDefinitionResult.Invalid)?.problems.orEmpty()

    private companion object {
        const val MAX = WidgetDefinitionParser.MAX_BYTES
        const val RESPONSE_BYTES = 16 * 1024
    }
}
