package xyz.lilsus.raylsuite.core.widgetspec

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Cryptographically secure random bytes from the platform. */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * A new purchase-attempt ID: 128 random bits, URL-safe. Replaying it returns the order's
 * credentials, so it is as sensitive as the order token.
 */
@OptIn(ExperimentalEncodingApi::class)
fun newWidgetRequestId(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(16))
