package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only installation/setup selection. Payment behavior remains in each provider. */
internal class RaylSelection(private val settings: Settings) {
    private val mutableWallet = MutableStateFlow(
        RaylWallet.entries.firstOrNull { it.name == settings.getStringOrNull("rayl.wallet") }
    )
    val wallet = mutableWallet.asStateFlow()
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
        completeWelcome()
        settings.putString("rayl.wallet", wallet.name)
        mutableWallet.value = wallet
    }

    /** Called after provider cleanup, or cancelling setup before a connection exists. */
    fun clear() {
        settings.remove("rayl.wallet")
        mutableWallet.value = null
    }
}

internal enum class RaylWallet { Blink, Nwc }
