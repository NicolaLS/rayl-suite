package xyz.lilsus.rayl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet

internal suspend fun clearSavedWallet(context: Context, wallet: RaylWallet): Boolean =
    withContext(Dispatchers.Default) {
        when (wallet) {
            RaylWallet.Blink -> clearSavedBlinkWallet(context, RAYL_BLINK)
            RaylWallet.Nwc -> clearSavedNwcWallet(context, RAYL_NWC)
        }
        true
    }
