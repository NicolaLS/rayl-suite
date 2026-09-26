package xyz.lilsus.rayl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.lilsus.rayl.providers.blink.clearSavedBlinkWallet
import xyz.lilsus.rayl.providers.nwc.clearSavedNwcWallet
import xyz.lilsus.rayl.providers.spark.clearSavedSparkWallet

internal suspend fun clearSavedWallet(wallet: RaylWallet): Boolean =
    withContext(Dispatchers.Default) {
        when (wallet) {
            RaylWallet.Blink -> clearSavedBlinkWallet(RAYL_BLINK)
            RaylWallet.Nwc -> clearSavedNwcWallet(RAYL_NWC)
            RaylWallet.Spark -> return@withContext clearSavedSparkWallet()
        }
        true
    }
