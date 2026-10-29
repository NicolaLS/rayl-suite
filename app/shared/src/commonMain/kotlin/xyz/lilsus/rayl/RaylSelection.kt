package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.raylsuite.core.model.WalletStartMode

/** Owns the single configured provider and temporary setup. See SPEC-wallet-lifecycle. */
internal class RaylSelection(private val settings: Settings) {
    private val mutableWallet = MutableStateFlow(configuredWallet(settings))
    val wallet = mutableWallet.asStateFlow()
    var startMode = WalletStartMode.Restore
        private set

    // Retained through Android rotation, but never restored from an abandoned process/session.
    var sessionId = Random.nextLong()
        private set
    private val preparationMutex = Mutex()
    private val mutablePreparation = MutableStateFlow(WalletPreparation.Cleaning)
    val preparation = mutablePreparation.asStateFlow()

    /** Delete unowned connections before any provider runtime or the chooser is mounted. */
    suspend fun prepare(clearSavedWallet: suspend (RaylWallet) -> Boolean) {
        preparationMutex.withLock {
            if (mutablePreparation.value == WalletPreparation.Ready) return
            mutablePreparation.value = WalletPreparation.Cleaning
            try {
                for (provider in RaylWallet.entries) {
                    if (provider != mutableWallet.value && !clearSavedWallet(provider)) {
                        mutablePreparation.value = WalletPreparation.Failed
                        return
                    }
                }
                mutablePreparation.value = WalletPreparation.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutablePreparation.value = WalletPreparation.Failed
            }
        }
    }

    /** Only successful connection setup becomes eligible for restoration on another launch. */
    fun connected(wallet: RaylWallet) {
        if (mutableWallet.value == wallet && mutablePreparation.value == WalletPreparation.Ready) {
            settings.putString("rayl.wallet", wallet.name)
        }
    }
    private val mutableWelcomeCompleted =
        MutableStateFlow(settings.getBoolean("rayl.welcome", false))
    val welcomeCompleted = mutableWelcomeCompleted.asStateFlow()

    fun completeWelcome() {
        settings.putBoolean("rayl.welcome", true)
        mutableWelcomeCompleted.value = true
    }

    fun choose(wallet: RaylWallet) {
        check(mutableWallet.value == null) {
            "Remove the current connection before choosing a wallet"
        }
        check(mutablePreparation.value == WalletPreparation.Ready) {
            "Finish connection cleanup before choosing a wallet"
        }
        completeWelcome()
        startMode = WalletStartMode.Fresh
        sessionId = Random.nextLong()
        mutableWallet.value = wallet
    }

    /** Called after provider cleanup, or cancelling setup before a connection exists. */
    fun clear() {
        mutablePreparation.value = WalletPreparation.Cleaning
        settings.remove("rayl.wallet")
        mutableWallet.value = null
    }
}

internal enum class RaylWallet { Rayl, Blink, Nwc, Spark }

/** The provider that completed setup on an earlier launch, if any. */
internal fun configuredWallet(settings: Settings): RaylWallet? =
    RaylWallet.entries.firstOrNull { it.name == settings.getStringOrNull("rayl.wallet") }

internal enum class WalletPreparation { Cleaning, Failed, Ready }
