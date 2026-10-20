package xyz.lilsus.rayl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet

internal suspend fun clearSavedWallet(wallet: RaylWallet): Boolean =
    withContext(Dispatchers.Default) {
        when (wallet) {
            RaylWallet.Blink -> clearSavedBlinkWallet(RAYL_BLINK)
            RaylWallet.Nwc -> clearSavedNwcWallet(RAYL_NWC)
        }
        true
    }
