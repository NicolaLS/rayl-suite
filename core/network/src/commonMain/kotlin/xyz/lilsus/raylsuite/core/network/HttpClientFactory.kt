package xyz.lilsus.raylsuite.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

fun createHttpClient(): HttpClient = platformHttpClient().config {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        exponentialDelay()
    }
}

fun createWebSocketHttpClient(): HttpClient = platformHttpClient().config {
    install(HttpTimeout) {
        connectTimeoutMillis = 7_000
        socketTimeoutMillis = 12_000
        requestTimeoutMillis = 12_000
    }
    install(WebSockets) {
        pingInterval = 5.seconds
    }
}

/**
 * For third-party widget providers: no cookies, no shared cache, no redirects and no retries, so
 * a provider only ever sees the request the user caused, on the host they approved.
 */
fun createWidgetHttpClient(): HttpClient = platformWidgetHttpClient().config {
    followRedirects = false
    expectSuccess = false
    // Providers may localise the text they return (rayl-widget/1 §4.1).
    defaultRequest { header(HttpHeaders.AcceptLanguage, preferredLanguages()) }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

internal expect fun platformHttpClient(): HttpClient

internal expect fun platformWidgetHttpClient(): HttpClient

/** The user's languages, most preferred first, as an Accept-Language value. */
internal expect fun preferredLanguages(): String
