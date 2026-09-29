package xyz.lilsus.raylsuite.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)

internal actual fun platformWidgetHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }
}

internal actual fun preferredLanguages(): String =
    android.os.LocaleList.getDefault().toLanguageTags().ifBlank { "en" }
