package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentEngine
import xyz.lilsus.rayl.providers.spark.application.payment.PaymentSessionLifecycle
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient

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
    applicationScope: CoroutineScope
) : WalletAccess {
    private val lifecycleJob = SupervisorJob(applicationScope.coroutineContext[Job])
    private val lifecycleScope = CoroutineScope(applicationScope.coroutineContext + lifecycleJob)
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<WalletAccessState>(WalletAccessState.Loading)
    private val mutableRemoved = MutableStateFlow(false)
    private var sdk: SparkSdkSession? = null
    private var connectingSdk: SparkSdkSession? = null
    private var started = false
    private var closeCompleted = false

    override val state: StateFlow<WalletAccessState> = mutableState.asStateFlow()
    override val removed: StateFlow<Boolean> = mutableRemoved.asStateFlow()

    override fun start() {
        lifecycleScope.launch {
            operationMutex.withLock {
                if (started) return@withLock
                started = true
                restoreOrResumeReset()
            }
        }
    }

    override suspend fun importWallet(mnemonic: String): ImportWalletResult = runOperation {
        operationMutex.withLock {
            mutableRemoved.value = false
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
    }

    override suspend fun retryConnection(): Unit = runOperation {
        operationMutex.withLock {
            if (mutableState.value == WalletAccessState.ResetRequired) {
                removeLocalWallet()
            } else {
                disconnectCurrent()
                restoreOrResumeReset()
            }
            Unit
        }
    }

    override suspend fun removeWallet(): RemoveWalletResult = runOperation {
        operationMutex.withLock { removeLocalWallet() }
    }

    override suspend fun close() {
        lifecycleJob.cancel()
        withContext(NonCancellable) {
            lifecycleJob.join()
            operationMutex.withLock {
                if (closeCompleted) return@withLock
                paymentLifecycle.detach()
                sdk?.disconnect()
                sdk = null
                connectingSdk?.disconnect()
                connectingSdk = null
                closeCompleted = true
            }
        }
    }

    private suspend fun <T> runOperation(block: suspend () -> T): T {
        val operation = lifecycleScope.async {
            currentCoroutineContext().ensureActive()
            block()
        }
        return try {
            operation.await()
        } catch (cancellation: CancellationException) {
            operation.cancel()
            withContext(NonCancellable) { operation.join() }
            throw cancellation
        }
    }

    private suspend fun removeLocalWallet(): RemoveWalletResult {
        mutableRemoved.value = false
        mutableState.value = WalletAccessState.Removing
        // Record removal before touching credentials so a restart can resume partial cleanup.
        if (!directories.beginReset()) {
            mutableState.value = WalletAccessState.ResetRequired
            return RemoveWalletResult.RESET_REQUIRED
        }
        return resumeReset()
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

    private suspend fun resumeReset(): RemoveWalletResult {
        val disconnected = disconnectCurrent() && discardConnectingSession()
        val credentialDeleted = vault.delete() != CredentialDeleteResult.FAILED
        val dataDeleted = directories.deleteWalletData()
        // Startup must keep seeing the marker until every cleanup step has succeeded.
        val finished = disconnected && credentialDeleted && dataDeleted && directories.finishReset()
        mutableState.value =
            if (finished) WalletAccessState.NoWallet else WalletAccessState.ResetRequired
        mutableRemoved.value = finished
        return if (finished) RemoveWalletResult.REMOVED else RemoveWalletResult.RESET_REQUIRED
    }

    private suspend fun connectApplicationSession(credential: WalletCredential): Boolean {
        currentCoroutineContext().ensureActive()
        if (sdk != null) return true
        if (!discardConnectingSession()) return false
        currentCoroutineContext().ensureActive()
        return try {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) {
                connector.connect(credential, directories.sdkDirectory, bootstrapConfig).also {
                    connectingSdk = it
                }
            } ?: return false
            currentCoroutineContext().ensureActive()
            paymentLifecycle.attach(connected.paymentClient)
            sdk = connected
            connectingSdk = null
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        } finally {
            // close owns cleanup after lifetime cancellation, including a session whose attachment
            // was interrupted. Until then, retain failed cleanup for a later retry or close.
            if (lifecycleJob.isActive) discardConnectingSession()
        }
    }

    private suspend fun discardConnectingSession(): Boolean = withContext(NonCancellable) {
        val connecting = connectingSdk ?: return@withContext true
        try {
            connecting.disconnect()
            connectingSdk = null
            true
        } catch (_: Throwable) {
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
            if (lifecycleJob.isActive) {
                try {
                    paymentLifecycle.attach(current.paymentClient)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {}
            }
            false
        }
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
