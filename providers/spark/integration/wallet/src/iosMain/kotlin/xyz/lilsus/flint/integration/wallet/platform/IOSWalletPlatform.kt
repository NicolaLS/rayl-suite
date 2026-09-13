@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.flint.integration.wallet.platform

import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecDecode
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import xyz.lilsus.flint.application.AppBootstrapConfig
import xyz.lilsus.flint.application.payment.DefaultPaymentEngine
import xyz.lilsus.flint.application.wallet.CredentialDeleteResult
import xyz.lilsus.flint.application.wallet.CredentialLoadResult
import xyz.lilsus.flint.application.wallet.CredentialStoreResult
import xyz.lilsus.flint.application.wallet.CredentialVault
import xyz.lilsus.flint.application.wallet.DefaultWalletAccess
import xyz.lilsus.flint.application.wallet.WalletAccess
import xyz.lilsus.flint.application.wallet.WalletCredential
import xyz.lilsus.flint.application.wallet.WalletDirectories
import xyz.lilsus.flint.integration.wallet.spark.BreezSparkSdkConnector

fun createIOSWalletAccess(bootstrapConfig: AppBootstrapConfig): WalletAccess {
    val directories = IOSWalletDirectories()
    val vault = IOSCredentialVault().also(IOSCredentialVault::purgeOrphanAfterFreshInstall)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paymentEngine = DefaultPaymentEngine(
        environment = bootstrapConfig.environment
    )
    val walletAccess = DefaultWalletAccess(
        bootstrapConfig = bootstrapConfig,
        vault = vault,
        directories = directories,
        connector = BreezSparkSdkConnector,
        payments = paymentEngine,
        paymentLifecycle = paymentEngine,
        applicationScope = applicationScope
    ).also(WalletAccess::start)
    return walletAccess
}

private class IOSWalletDirectories : WalletDirectories {
    private val fileManager = NSFileManager.defaultManager
    private val baseDirectory = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).first() as String
        ) + "/flint-wallet"
    private val resetMarker = "$baseDirectory/reset-pending"

    override val sdkDirectory: String
        get() = "$baseDirectory/breez-spark".also { path ->
            check(fileManager.createDirectoryAtPath(path, true, null, null))
        }

    init {
        fileManager.createDirectoryAtPath(
            baseDirectory,
            withIntermediateDirectories = true,
            attributes = mapOf(
                "NSFileProtectionKey" to NSFileProtectionCompleteUntilFirstUserAuthentication
            ),
            error = null
        )
    }

    override fun beginReset(): Boolean = NSData().writeToFile(resetMarker, atomically = true)

    override fun resetPending(): Boolean = fileManager.fileExistsAtPath(resetMarker)

    override fun deleteWalletData(): Boolean = deleteIfPresent(sdkDirectory)

    override fun finishReset(): Boolean = deleteIfPresent(resetMarker)

    private fun deleteIfPresent(path: String): Boolean =
        !fileManager.fileExistsAtPath(path) || fileManager.removeItemAtPath(path, null)
}

private class IOSCredentialVault : CredentialVault {
    override suspend fun load(): CredentialLoadResult = withDictionary(loadMap()) { query ->
        memScoped {
            val result = alloc<CPointerVarOf<CFTypeRef>>()
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                        ?: return@memScoped CredentialLoadResult.Corrupt
                    val value = NSString.create(data, NSUTF8StringEncoding)?.toString()
                        ?: return@memScoped CredentialLoadResult.Corrupt
                    try {
                        CredentialLoadResult.Available(WalletCredential.fromMnemonic(value))
                    } catch (_: IllegalArgumentException) {
                        CredentialLoadResult.Corrupt
                    }
                }

                errSecItemNotFound -> CredentialLoadResult.Absent

                errSecInteractionNotAllowed, errSecAuthFailed -> CredentialLoadResult.Unavailable

                errSecDecode -> CredentialLoadResult.Corrupt

                else -> if (status ==
                    errSecItemNotFound
                ) {
                    CredentialLoadResult.Absent
                } else {
                    CredentialLoadResult.Invalidated
                }
            }
        }
    }

    override suspend fun store(credential: WalletCredential): CredentialStoreResult {
        if (itemExists()) return CredentialStoreResult.ALREADY_EXISTS
        val data =
            NSString.create(string = credential.value).dataUsingEncoding(NSUTF8StringEncoding)
                ?: return CredentialStoreResult.FAILED
        val attributes = baseMap() + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData to data
        )
        return withDictionary(attributes) { query ->
            when (SecItemAdd(query, null)) {
                errSecSuccess -> CredentialStoreResult.STORED
                errSecInteractionNotAllowed, errSecAuthFailed -> CredentialStoreResult.UNAVAILABLE
                else -> CredentialStoreResult.FAILED
            }
        }
    }

    override suspend fun delete(): CredentialDeleteResult {
        val existed = itemExists()
        return withDictionary(baseMap()) { query ->
            when (SecItemDelete(query)) {
                errSecSuccess -> CredentialDeleteResult.DELETED

                errSecItemNotFound ->
                    if (existed) {
                        CredentialDeleteResult.FAILED
                    } else {
                        CredentialDeleteResult.ABSENT
                    }

                else -> CredentialDeleteResult.FAILED
            }
        }
    }

    fun purgeOrphanAfterFreshInstall() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (!defaults.boolForKey(INSTALL_MARKER)) {
            withDictionary(baseMap()) { SecItemDelete(it) }
            defaults.setBool(true, INSTALL_MARKER)
        }
    }

    private fun itemExists(): Boolean = withDictionary(existenceMap()) {
        SecItemCopyMatching(it, null) == errSecSuccess
    }

    private fun baseMap(): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to ACCOUNT
    )

    private fun existenceMap(): Map<Any?, Any?> = baseMap() + mapOf(
        kSecMatchLimit to kSecMatchLimitOne
    )

    private fun loadMap(): Map<Any?, Any?> = baseMap() + mapOf(
        kSecMatchLimit to kSecMatchLimitOne,
        kSecReturnData to kCFBooleanTrue
    )

    private inline fun <T> withDictionary(
        values: Map<Any?, Any?>,
        block: (CFDictionaryRef) -> T
    ): T {
        val retained = checkNotNull(CFBridgingRetain(values))
        return try {
            block(retained.reinterpret())
        } finally {
            CFRelease(retained)
        }
    }

    companion object {
        private const val SERVICE = "xyz.lilsus.flint.wallet"
        private const val ACCOUNT = "singleton-mnemonic-v1"
        private const val INSTALL_MARKER = "flint.install.marker.v1"
    }
}
