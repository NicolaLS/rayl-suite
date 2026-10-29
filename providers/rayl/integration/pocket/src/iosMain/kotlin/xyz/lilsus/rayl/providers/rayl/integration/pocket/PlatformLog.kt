package xyz.lilsus.rayl.providers.rayl.integration.pocket

import platform.Foundation.NSLog

internal actual fun platformLog(tag: String, message: String) {
    NSLog("$tag ${message.replace("%", "%%")}")
}
