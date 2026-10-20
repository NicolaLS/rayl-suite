package xyz.lilsus.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkDeepLinks
import xyz.lilsus.rayl.providers.blink.BlinkIosExperience
import xyz.lilsus.rayl.providers.nwc.NwcDeepLinks
import xyz.lilsus.rayl.providers.nwc.NwcIosExperience
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

class RaylSnapshot(
    val wallet: String?,
    val wallets: List<String>,
    val blinkExperience: BlinkIosExperience?,
    val nwcExperience: NwcIosExperience?,
    val preparingWallets: Boolean,
    val walletCleanupFailed: Boolean,
    val welcomeCompleted: Boolean,
    val connected: Boolean,
    val canCancelSetup: Boolean,
    val colorScheme: String,
    val text: Map<String, String>,
    val message: String?
)

private data class RaylExperienceState(
    val connected: Boolean = false,
    val canCancelSetup: Boolean = false,
    val colorScheme: String = "system"
)

object RaylIosApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appSettings = createAppSettings()
    private val selection = RaylSelection(appSettings)
    private val language = createLanguageRepository()
    private val experienceState = MutableStateFlow(RaylExperienceState())
    private val messageKey = MutableStateFlow<String?>(null)
    private var blink: BlinkIosExperience? = null
    private var nwc: NwcIosExperience? = null
    private var cancellations = emptyList<() -> Unit>()

    init {
        retryWalletCleanup()
        scope.launch {
            RaylDeepLinks.events.collect { uri ->
                selection.preparation.first { it == WalletPreparation.Ready }
                val selected = selection.wallet.value
                val hasConnection = when (selected) {
                    RaylWallet.Blink -> blinkExperience()?.isConnected() == true
                    RaylWallet.Nwc -> nwcExperience()?.isConnected() == true
                    null -> false
                }
                if (uri.substringBefore(':').equals("nostr+walletconnect", true)) {
                    if (selected == null) {
                        choose("nwc")
                        NwcDeepLinks.emit(uri)
                    } else if (selected == RaylWallet.Nwc && !hasConnection) {
                        NwcDeepLinks.emit(uri)
                    } else {
                        messageKey.value = "connection_exists"
                    }
                } else if (!hasConnection) {
                    messageKey.value = "connect_to_pay"
                } else {
                    when (selected) {
                        RaylWallet.Blink -> BlinkDeepLinks.emit(uri)
                        RaylWallet.Nwc -> NwcDeepLinks.emit(uri)
                        null -> Unit
                    }
                }
            }
        }
    }

    fun observe(onChange: (RaylSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                combine(selection.wallet, selection.preparation) { wallet, preparation ->
                    wallet to preparation
                },
                selection.welcomeCompleted,
                experienceState,
                language.preference,
                messageKey
            ) {
                    selectionState,
                    welcome,
                    experience,
                    _,
                    message
                ->
                val (wallet, preparation) = selectionState
                val ready = preparation == WalletPreparation.Ready
                val keys =
                    listOf(
                        "welcome_title",
                        "welcome_body",
                        "get_started",
                        "choose_wallet",
                        "choose_body",
                        "blink_title",
                        "blink_body",
                        "nwc_title",
                        "nwc_body",
                        "choose_another",
                        "close",
                        "wallet_cleanup_failed",
                        "wallet_cleanup_retry"
                    )
                val text = keys.associateWith { nativeString(NativeStringResource("RaylApp", it)) }
                // SwiftUI may render this snapshot after removal has cleared the live selection.
                // Keep its experience with it; rendering must never resolve a different session.
                val blinkExperience = if (ready &&
                    wallet == RaylWallet.Blink
                ) {
                    blinkExperience()
                } else {
                    null
                }
                val nwcExperience = if (ready && wallet == RaylWallet.Nwc) nwcExperience() else null
                val mountedWallet = when {
                    blinkExperience != null -> "blink"
                    nwcExperience != null -> "nwc"
                    else -> null
                }
                val wallets = RaylWallet.entries.map { it.name.lowercase() }
                RaylSnapshot(
                    mountedWallet,
                    wallets,
                    blinkExperience,
                    nwcExperience,
                    !ready,
                    preparation == WalletPreparation.Failed,
                    welcome,
                    mountedWallet != null && experience.connected,
                    mountedWallet != null && experience.canCancelSetup,
                    if (mountedWallet == null) {
                        // The provider owns preference writes; reload after it unmounts.
                        when (DefaultThemePreferences(appSettings).current()) {
                            ThemePreference.Light -> "light"
                            ThemePreference.Dark -> "dark"
                            ThemePreference.System -> "system"
                        }
                    } else {
                        experience.colorScheme
                    },
                    text,
                    message?.let {
                        nativeString(NativeStringResource("RaylApp", it))
                    }
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun completeWelcome() = selection.completeWelcome()
    fun dismissMessage() {
        messageKey.value = null
    }
    fun choose(wallet: String) {
        if (selection.wallet.value != null ||
            selection.preparation.value != WalletPreparation.Ready
        ) {
            return
        }
        val selected = when (wallet) {
            "blink" -> RaylWallet.Blink
            "nwc" -> RaylWallet.Nwc
            else -> return
        }
        selection.choose(selected)
    }

    private fun blinkExperience(): BlinkIosExperience? {
        // combine can still deliver a prior selection while the latest state propagates.
        if (selection.wallet.value != RaylWallet.Blink) return null
        return blink ?: BlinkIosExperience(RAYL_BLINK, selection.startMode).also { experience ->
            blink = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (blink === experience) {
                            if (connected) selection.connected(RaylWallet.Blink)
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeCanCancelSetup { canCancel ->
                        if (blink === experience) {
                            experienceState.update { it.copy(canCancelSetup = canCancel) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (blink === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    experience.observeRemoved {
                        if (it) scope.launch { if (blink === experience) leave() }
                    }
                )
        }
    }

    private fun nwcExperience(): NwcIosExperience? {
        if (selection.wallet.value != RaylWallet.Nwc) return null
        return nwc ?: NwcIosExperience(RAYL_NWC, selection.startMode).also { experience ->
            nwc = experience
            cancellations =
                listOf(
                    experience.observeConnected { connected ->
                        if (nwc === experience) {
                            if (connected) selection.connected(RaylWallet.Nwc)
                            experienceState.update { it.copy(connected = connected) }
                        }
                    },
                    experience.observeCanCancelSetup { canCancel ->
                        if (nwc === experience) {
                            experienceState.update { it.copy(canCancelSetup = canCancel) }
                        }
                    },
                    experience.observeTheme { colorScheme ->
                        if (nwc === experience) {
                            experienceState.update { it.copy(colorScheme = colorScheme) }
                        }
                    },
                    experience.observeRemoved {
                        if (it) scope.launch { if (nwc === experience) leave() }
                    }
                )
        }
    }

    fun cancelSetup() {
        val canCancel = when (selection.wallet.value) {
            RaylWallet.Blink -> blink?.canCancelSetup() == true
            RaylWallet.Nwc -> nwc?.canCancelSetup() == true
            null -> false
        }
        if (!canCancel) return
        blink?.cancelSetup()
        nwc?.cancelSetup()
    }

    fun retryWalletCleanup() {
        scope.launch { selection.prepare(::clearSavedWallet) }
    }

    private fun leave() {
        cancellations.forEach { it() }
        cancellations = emptyList()
        blink?.clear()
        nwc?.clear()
        blink = null
        nwc = null
        experienceState.update {
            it.copy(
                connected = false,
                canCancelSetup = false
            )
        }
        messageKey.value = null
        RaylDeepLinks.clear()
        BlinkDeepLinks.clear()
        NwcDeepLinks.clear()
        selection.clear()
        retryWalletCleanup()
    }
}
