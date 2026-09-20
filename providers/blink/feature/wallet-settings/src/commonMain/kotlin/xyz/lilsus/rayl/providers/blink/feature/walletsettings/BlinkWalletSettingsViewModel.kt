package xyz.lilsus.rayl.providers.blink.feature.walletsettings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkFundingWallet
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.rayl.providers.blink.ui.BlinkUiError

class BlinkWalletSettingsViewModel(
    private val blinkWallet: BlinkWallet,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mutableUiState =
        MutableStateFlow(
            BlinkWalletSettingsUiState(
                selectedWallet = blinkWallet.selectedFundingWallet.value
            )
        )
    val uiState: StateFlow<BlinkWalletSettingsUiState> = mutableUiState.asStateFlow()

    fun loadFundingWallets() {
        if (mutableUiState.value.isLoading) return
        scope.launch {
            mutableUiState.update {
                it.copy(
                    wallets = emptyList(),
                    isLoading = true,
                    selectionUnavailable = false,
                    error = null
                )
            }
            try {
                val wallets = blinkWallet.refreshFundingWallets()
                val selectedWallet = blinkWallet.selectedFundingWallet.value
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        wallets = wallets,
                        selectedWallet = selectedWallet,
                        selectionUnavailable = selectedWallet == null
                    )
                }
            } catch (error: BlinkApiException) {
                mutableUiState.update {
                    it.copy(isLoading = false, error = BlinkUiError.Api(error.error))
                }
            } catch (error: BlinkConnectionException) {
                mutableUiState.update {
                    it.copy(isLoading = false, error = BlinkUiError.Connection(error.error))
                }
            } catch (error: Exception) {
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        error = BlinkUiError.Unexpected(error.message)
                    )
                }
            }
        }
    }

    fun selectFundingWallet(walletId: String) {
        val wallet = mutableUiState.value.wallets.firstOrNull { it.id == walletId } ?: return
        try {
            blinkWallet.selectFundingWallet(wallet)
            mutableUiState.update {
                it.copy(
                    selectedWallet = wallet,
                    selectionUnavailable = false,
                    error = null
                )
            }
        } catch (error: BlinkConnectionException) {
            mutableUiState.update {
                it.copy(error = BlinkUiError.Connection(error.error))
            }
        } catch (error: Exception) {
            mutableUiState.update {
                it.copy(error = BlinkUiError.Unexpected(error.message))
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}

data class BlinkWalletSettingsUiState(
    val selectedWallet: BlinkFundingWallet? = null,
    val wallets: List<BlinkFundingWallet> = emptyList(),
    val isLoading: Boolean = false,
    val selectionUnavailable: Boolean = false,
    val error: BlinkUiError? = null
)
