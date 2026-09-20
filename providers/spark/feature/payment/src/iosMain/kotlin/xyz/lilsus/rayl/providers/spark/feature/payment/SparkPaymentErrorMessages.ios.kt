package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.resolveNative

fun getSparkPaymentErrorMessageFor(error: PaymentUiError): String =
    error.toLocalizedText().resolveNative()
