package xyz.lilsus.rayl.providers.blink.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.resolveNative

fun getBlinkPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolveNative()
