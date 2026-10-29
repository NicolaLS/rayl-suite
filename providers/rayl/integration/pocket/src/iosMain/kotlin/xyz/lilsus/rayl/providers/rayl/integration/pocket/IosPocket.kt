@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import xyz.lilsus.raylsuite.core.settings.withKeychainDictionary

fun createIosRaylPocket(config: PocketConfig): RaylPocket {
    val directory = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).first() as String
        ) + "/rayl-pocket/${config.network.storageName}/sdk"
    val fileManager = NSFileManager.defaultManager
    return RaylPocket(
        keys = IosPocketKeyStore(config.network.storageName),
        connector = BreezPocketConnector(config) {
            directory.also {
                check(fileManager.createDirectoryAtPath(it, true, null, null))
                // Like Android's no-backup directory: wallet data stays out of device backups.
                NSURL.fileURLWithPath(it).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
            }
        },
        eraseWalletData = {
            !fileManager.fileExistsAtPath(directory) ||
                fileManager.removeItemAtPath(directory, null)
        }
    )
}

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned {
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "Secure random generation failed: $status" }
    return bytes
}

/**
 * A device-only Keychain item: it survives reinstalling but never restores to another device.
 * See the production TODO on [PocketKey].
 */
private class IosPocketKeyStore(private val network: String) : PocketKeyStore {
    override suspend fun load(): PocketKeyLoad = withKeychainDictionary(
        baseQuery() + mapOf(kSecMatchLimit to kSecMatchLimitOne, kSecReturnData to kCFBooleanTrue)
    ) { query ->
        memScoped {
            val result = alloc<CPointerVarOf<CFTypeRef>>()
            when (SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                    val phrase = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
                    if (phrase == null) {
                        PocketKeyLoad.Unavailable
                    } else {
                        PocketKeyLoad.Present(PocketKey(phrase))
                    }
                }

                errSecItemNotFound -> PocketKeyLoad.Absent

                else -> PocketKeyLoad.Unavailable
            }
        }
    }

    override suspend fun add(key: PocketKey): Boolean {
        val data = NSString.create(string = key.phrase).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false
        val attributes = baseQuery() + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData to data
        )
        return withKeychainDictionary(attributes) { SecItemAdd(it, null) == errSecSuccess }
    }

    override suspend fun delete(): Boolean = withKeychainDictionary(baseQuery()) {
        when (SecItemDelete(it)) {
            errSecSuccess, errSecItemNotFound -> true
            else -> false
        }
    }

    private fun baseQuery(): Map<CFStringRef?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to NSString.create(string = "xyz.lilsus.rayl.pocket"),
        kSecAttrAccount to NSString.create(string = "pocket-key-v1-$network")
    )
}
