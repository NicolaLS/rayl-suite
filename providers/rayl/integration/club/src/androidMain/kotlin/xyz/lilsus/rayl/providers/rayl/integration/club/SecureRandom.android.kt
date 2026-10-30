package xyz.lilsus.rayl.providers.rayl.integration.club

import java.security.SecureRandom

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)
