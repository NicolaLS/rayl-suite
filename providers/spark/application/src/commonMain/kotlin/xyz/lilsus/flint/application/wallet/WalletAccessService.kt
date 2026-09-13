package xyz.lilsus.flint.application.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.flint.application.AppBootstrapConfig
import xyz.lilsus.flint.application.payment.PaymentEngine
import xyz.lilsus.flint.application.payment.PaymentSessionLifecycle
import xyz.lilsus.flint.application.payment.SparkPaymentClient

interface SparkSdkSession {
    val paymentClient: SparkPaymentClient
    suspend fun disconnect()
}

fun interface SparkSdkConnector {
    suspend fun connect(
        credential: WalletCredential,
        storageDirectory: String,
        bootstrapConfig: AppBootstrapConfig
    ): SparkSdkSession
}

interface WalletDirectories {
    val sdkDirectory: String
    fun beginReset(): Boolean
    fun resetPending(): Boolean
    fun deleteWalletData(): Boolean
    fun finishReset(): Boolean
}

class DefaultWalletAccess(
    private val bootstrapConfig: AppBootstrapConfig,
    private val vault: CredentialVault,
    private val directories: WalletDirectories,
    private val connector: SparkSdkConnector,
    override val payments: PaymentEngine,
    private val paymentLifecycle: PaymentSessionLifecycle,
    private val applicationScope: CoroutineScope
) : WalletAccess {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<WalletAccessState>(WalletAccessState.Loading)
    private var sdk: SparkSdkSession? = null
    private var started = false

    override val state: StateFlow<WalletAccessState> = mutableState.asStateFlow()

    override fun start() {
        if (started) return
        started = true
        applicationScope.launch { operationMutex.withLock { restoreOrResumeReset() } }
    }

    override suspend fun importWallet(mnemonic: String): ImportWalletResult =
        operationMutex.withLock {
            val existing = vault.load()
            if (existing !is CredentialLoadResult.Absent) {
                if (existing !is CredentialLoadResult.Available) {
                    applyCredentialLoadState(existing)
                }
                return@withLock ImportWalletResult.ALREADY_CONFIGURED
            }

            val credential = try {
                WalletCredential.fromMnemonic(mnemonic)
            } catch (_: IllegalArgumentException) {
                mutableState.value = WalletAccessState.NoWallet
                return@withLock ImportWalletResult.INVALID_MNEMONIC
            }

            mutableState.value = WalletAccessState.Connecting
            if (!connectApplicationSession(credential)) {
                directories.deleteWalletData()
                mutableState.value = WalletAccessState.NoWallet
                return@withLock ImportWalletResult.CONNECTION_FAILED
            }

            when (vault.store(credential)) {
                CredentialStoreResult.STORED -> {
                    mutableState.value = WalletAccessState.Connected
                    return@withLock ImportWalletResult.IMPORTED
                }

                CredentialStoreResult.ALREADY_EXISTS -> {
                    disconnectCurrent()
                    restoreOrResumeReset()
                    return@withLock ImportWalletResult.ALREADY_CONFIGURED
                }

                CredentialStoreResult.UNAVAILABLE -> {
                    discardUnstoredSession()
                    mutableState.value = WalletAccessState.NoWallet
                    return@withLock ImportWalletResult.CREDENTIAL_STORE_FAILED
                }

                CredentialStoreResult.INVALIDATED -> {
                    discardUnstoredSession()
                    mutableState.value = WalletAccessState.NoWallet
                    return@withLock ImportWalletResult.CREDENTIAL_STORE_FAILED
                }

                CredentialStoreResult.FAILED -> {
                    discardUnstoredSession()
                    mutableState.value = WalletAccessState.NoWallet
                    return@withLock ImportWalletResult.CREDENTIAL_STORE_FAILED
                }
            }
        }

    override suspend fun retryConnection() = operationMutex.withLock {
        disconnectCurrent()
        restoreOrResumeReset()
    }

    override suspend fun removeWallet(): RemoveWalletResult = operationMutex.withLock {
        mutableState.value = WalletAccessState.Removing
        val markerCreated = directories.beginReset()
        val disconnected = disconnectCurrent()
        val credentialDeleted = vault.delete() != CredentialDeleteResult.FAILED
        val dataDeleted = directories.deleteWalletData()
        val markerFinished = directories.finishReset()

        if (markerCreated && disconnected && credentialDeleted && dataDeleted && markerFinished) {
            mutableState.value = WalletAccessState.NoWallet
            RemoveWalletResult.REMOVED
        } else {
            mutableState.value = WalletAccessState.ResetRequired
            RemoveWalletResult.RESET_REQUIRED
        }
    }

    private suspend fun restoreOrResumeReset() {
        mutableState.value = WalletAccessState.Loading
        if (directories.resetPending()) {
            resumeReset()
            return
        }

        when (val result = vault.load()) {
            is CredentialLoadResult.Available -> {
                mutableState.value = WalletAccessState.Connecting
                mutableState.value = if (connectApplicationSession(result.credential)) {
                    WalletAccessState.Connected
                } else {
                    WalletAccessState.ReconnectRequired
                }
            }

            else -> applyCredentialLoadState(result)
        }
    }

    private suspend fun resumeReset() {
        val disconnected = disconnectCurrent()
        val credentialDeleted = vault.delete() != CredentialDeleteResult.FAILED
        val dataDeleted = directories.deleteWalletData()
        val markerFinished = directories.finishReset()
        val finished = disconnected && credentialDeleted && dataDeleted && markerFinished
        mutableState.value =
            if (finished) WalletAccessState.NoWallet else WalletAccessState.ResetRequired
    }

    private suspend fun connectApplicationSession(credential: WalletCredential): Boolean {
        if (sdk != null) return true
        return try {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) {
                connector.connect(credential, directories.sdkDirectory, bootstrapConfig)
            } ?: return false
            try {
                paymentLifecycle.attach(connected.paymentClient)
            } catch (error: Throwable) {
                ignoreCleanupFailure { connected.disconnect() }
                throw error
            }
            sdk = connected
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            sdk = null
            false
        }
    }

    private suspend fun discardUnstoredSession() {
        disconnectCurrent()
        directories.deleteWalletData()
    }

    private suspend fun disconnectCurrent(): Boolean {
        val current = sdk ?: return true
        return try {
            paymentLifecycle.detach()
            current.disconnect()
            sdk = null
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            try {
                paymentLifecycle.attach(current.paymentClient)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {}
            false
        }
    }

    private suspend fun ignoreCleanupFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {}
    }

    private fun applyCredentialLoadState(result: CredentialLoadResult) {
        mutableState.value = when (result) {
            CredentialLoadResult.Absent -> WalletAccessState.NoWallet

            CredentialLoadResult.Unavailable -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.UNAVAILABLE
            )

            CredentialLoadResult.Invalidated -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.INVALIDATED
            )

            CredentialLoadResult.Corrupt -> WalletAccessState.CredentialProblem(
                CredentialProblemKind.CORRUPT
            )

            is CredentialLoadResult.Available -> error("Available credentials must be connected")
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 30_000L
    }
}
