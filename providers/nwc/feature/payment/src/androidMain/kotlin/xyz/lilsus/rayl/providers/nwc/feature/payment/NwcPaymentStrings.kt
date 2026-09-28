package xyz.lilsus.rayl.providers.nwc.feature.payment

import androidx.annotation.StringRes
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey

/** Android resources for NWC's own payment text; shared payment text resolves itself. */
@StringRes
fun nwcPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is NwcPaymentTextKey -> when (key) {
        NwcPaymentTextKey.ErrorMissingWalletConnection ->
            R.string.error_missing_wallet_connection

        NwcPaymentTextKey.ErrorPaymentRejectedGeneric ->
            R.string.error_payment_rejected_generic

        NwcPaymentTextKey.ErrorPaymentRejectedMessage ->
            R.string.error_payment_rejected_message

        NwcPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        NwcPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message

        NwcPaymentTextKey.ErrorRelayConnectionFailed ->
            R.string.error_relay_connection_failed
    }

    else -> error("Unknown NWC payment text key: ${key.table}.${key.key}")
}
