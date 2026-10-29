package xyz.lilsus.rayl.providers.rayl.integration.pocket

import android.util.Log

internal actual fun platformLog(tag: String, message: String) {
    Log.i(tag, message)
}
