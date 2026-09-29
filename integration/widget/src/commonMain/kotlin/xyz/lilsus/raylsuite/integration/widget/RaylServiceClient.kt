package xyz.lilsus.raylsuite.integration.widget

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.RaylBackend
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.UsageReport

/** The Rayl backend's catalog and usage endpoints. */
interface RaylServiceApi {
    /** Null when the backend could not be reached or answered with something unusable. */
    suspend fun fetchCatalog(): ServiceCatalog?

    /** Best effort: a lost report is not retried. */
    suspend fun reportUsage(report: UsageReport)
}

class RaylServiceClient(
    private val backend: RaylBackend,
    private val http: HttpClient = createHttpClient()
) : RaylServiceApi {
    override suspend fun fetchCatalog(): ServiceCatalog? = attempt {
        val response = http.get(backend.baseUrl + RaylApi.CATALOG_PATH)
        if (!response.status.isSuccess()) return@attempt null
        json.decodeFromString(ServiceCatalog.serializer(), response.bodyAsText())
    }

    override suspend fun reportUsage(report: UsageReport) {
        attempt {
            http.post(backend.baseUrl + RaylApi.USAGE_PATH) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(UsageReport.serializer(), report))
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
