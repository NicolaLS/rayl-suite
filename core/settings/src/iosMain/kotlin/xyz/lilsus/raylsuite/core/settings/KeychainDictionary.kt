@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.raylsuite.core.settings

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString

/** Security queries need native CF values; bridging a Kotlin map wraps CF pointers as objects. */
inline fun <T> withKeychainDictionary(
    values: Map<CFStringRef?, Any?>,
    block: (CFDictionaryRef) -> T
): T = memScoped {
    val retainedValues =
        values.values.map { value ->
            when (value) {
                is NSData, is NSString -> checkNotNull(CFBridgingRetain(value))
                else -> value as CFTypeRef?
            }
        }
    return try {
        val keys = allocArrayOf(*values.keys.toTypedArray())
        val nativeValues = allocArrayOf(*retainedValues.toTypedArray())
        val dictionary =
            checkNotNull(
                CFDictionaryCreate(
                    allocator = kCFAllocatorDefault,
                    keys = keys.reinterpret(),
                    values = nativeValues.reinterpret(),
                    numValues = values.size.convert(),
                    keyCallBacks = null,
                    valueCallBacks = null
                )
            )
        try {
            block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    } finally {
        values.values.zip(retainedValues).forEach { (value, retained) ->
            if (value is NSData || value is NSString) CFRelease(retained)
        }
    }
}
