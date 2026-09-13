package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.feature.paymentui.components.PaymentResultPresentation

fun PaymentScreenState.toResultPresentation(): PaymentResultPresentation = when (this) {
    is PaymentScreenState.Success ->
        PaymentResultPresentation.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentScreenState.Error -> PaymentResultPresentation.Error(message)

    else -> error("Only terminal payment states have result presentation")
}
