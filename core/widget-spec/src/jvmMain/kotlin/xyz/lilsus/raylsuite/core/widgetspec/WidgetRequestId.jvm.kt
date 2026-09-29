package xyz.lilsus.raylsuite.core.widgetspec

import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray =
    ByteArray(count).also(random::nextBytes)
