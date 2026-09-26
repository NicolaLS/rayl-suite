@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.raylsuite.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDictionaryGetCount
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

class KeychainDictionaryTest {
    @Test
    fun securityQueriesContainNativeValuesInsteadOfKotlinPointerWrappers() {
        // A standalone native test has no Keychain entitlement. Check the FFI boundary directly:
        // bridging a Kotlin map would box its CF pointers and fail these native key/value lookups.
        val service = NSString.create(string = "xyz.lilsus.rayl.test")
        val data =
            checkNotNull(
                NSString.create(string = "test-value").dataUsingEncoding(NSUTF8StringEncoding)
            )
        withKeychainDictionary(
            mapOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecReturnData to kCFBooleanTrue,
                kSecValueData to data
            )
        ) { query ->
            assertEquals(4L, CFDictionaryGetCount(query))
            assertEquals(kSecClassGenericPassword, CFDictionaryGetValue(query, kSecClass))
            assertEquals(kCFBooleanTrue, CFDictionaryGetValue(query, kSecReturnData))
            assertEquals(
                CFStringGetTypeID(),
                CFGetTypeID(CFDictionaryGetValue(query, kSecAttrService))
            )
            assertEquals(CFDataGetTypeID(), CFGetTypeID(CFDictionaryGetValue(query, kSecValueData)))
        }
    }
}
