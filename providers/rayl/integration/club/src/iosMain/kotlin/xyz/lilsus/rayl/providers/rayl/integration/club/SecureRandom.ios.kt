@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.rayl.providers.rayl.integration.club

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned {
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "Secure random generation failed: $status" }
    return bytes
}
