package xyz.lilsus.rayl.providers.nwc.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.resolveNative

fun getNwcPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolveNative()
