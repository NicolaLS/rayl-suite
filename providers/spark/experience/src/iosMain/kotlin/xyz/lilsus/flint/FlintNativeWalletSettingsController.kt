package xyz.lilsus.flint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.flint.application.wallet.WalletAccess
import xyz.lilsus.flint.application.wallet.WalletAccessState
import xyz.lilsus.flint.feature.walletconnection.NativeFlintWalletConnectionText
import xyz.lilsus.flint.feature.walletconnection.WalletAction
import xyz.lilsus.flint.feature.walletconnection.WalletMessage
import xyz.lilsus.flint.feature.walletconnection.WalletViewModel
import xyz.lilsus.flint.feature.walletconnection.nativeFlintWalletConnectionText
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.walletmanagement.nativeWalletManagementText

data class FlintNativeWalletSettingsSnapshot(
    val settingsTitle: String,
    val settingsSubtitle: String,
    val screenTitle: String,
    val emptyDescription: String,
    val addTitle: String,
    val removeTitle: String,
    val removeConfirmationTitle: String,
    val removeConfirmationBody: String,
    val cancelTitle: String,
    val walletId: String?,
    val walletTitle: String?,
    val walletDetails: List<String>,
    val isWorking: Boolean,
    val errorMessage: String?
)

class FlintNativeWalletSettingsController internal constructor(
    walletAccess: WalletAccess,
    private val networkLabel: String,
    languageChanges: Flow<*>,
    private val isSubmitting: StateFlow<Boolean>,
    private val appName: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wallet = WalletViewModel(walletAccess)
    private val snapshot = MutableStateFlow<FlintNativeWalletSettingsSnapshot?>(null)

    init {
        scope.launch {
            combine(wallet.state, languageChanges) { _, _ -> Unit }
                .collect { publishSnapshot() }
        }
    }

    fun observe(onChange: (FlintNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun clear() {
        scope.cancel()
        wallet.dispose()
        snapshot.value = null
    }

    fun removeWallet() {
        if (!isSubmitting.value) wallet.dispatch(WalletAction.ConfirmRemoval)
    }

    private suspend fun publishSnapshot() {
        val state = wallet.state.value
        val management = nativeWalletManagementText()
        val connection = nativeFlintWalletConnectionText(appName)
        val title =
            nativeString(
                NativeStringResource(table = "SparkExperience", key = "settings_wallet_title")
            )
        val subtitle =
            nativeString(
                NativeStringResource(table = "SparkExperience", key = "settings_wallet_subtitle")
            )
        val connected = state.access == WalletAccessState.Connected

        snapshot.value =
            FlintNativeWalletSettingsSnapshot(
                settingsTitle = title,
                settingsSubtitle = subtitle,
                screenTitle = management.screenTitle,
                emptyDescription = management.emptyDescription,
                addTitle = management.addTitle,
                removeTitle = connection.removeConfirm,
                removeConfirmationTitle = connection.removeTitle,
                removeConfirmationBody = connection.removeBody,
                cancelTitle = connection.cancel,
                walletId = if (connected) FLINT_WALLET_ID else null,
                walletTitle = if (connected) title else null,
                walletDetails = if (connected) listOf(subtitle, networkLabel) else emptyList(),
                isWorking = state.access == WalletAccessState.Removing,
                errorMessage = state.message?.message(connection)
            )
    }

    private companion object {
        const val FLINT_WALLET_ID = "spark"
    }
}

private fun WalletMessage.message(text: NativeFlintWalletConnectionText): String = when (this) {
    WalletMessage.ALREADY_CONFIGURED -> text.errorAlreadyConfigured
    WalletMessage.INVALID_MNEMONIC -> text.errorInvalidMnemonic
    WalletMessage.CONNECTION_FAILED -> text.errorConnection
    WalletMessage.CREDENTIAL_STORE_FAILED -> text.errorStorage
    WalletMessage.RESET_REQUIRED -> text.errorReset
}
