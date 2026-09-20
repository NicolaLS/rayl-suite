package xyz.lilsus.rayl.providers.blink.ui

import xyz.lilsus.raylsuite.core.ui.resources.resolveNative

fun nativeBlinkErrorMessageFor(error: BlinkUiError): String = error.text().resolveNative()
