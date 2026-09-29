package xyz.lilsus.raylsuite.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSLocale
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.preferredLanguages

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

internal actual fun platformWidgetHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            setHTTPShouldSetCookies(false)
            setHTTPCookieAcceptPolicy(
                NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
            )
            setHTTPCookieStorage(null)
            setURLCache(null)
            setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        }
    }
}

internal actual fun preferredLanguages(): String =
    NSLocale.preferredLanguages.take(3).joinToString(",").ifBlank { "en" }
