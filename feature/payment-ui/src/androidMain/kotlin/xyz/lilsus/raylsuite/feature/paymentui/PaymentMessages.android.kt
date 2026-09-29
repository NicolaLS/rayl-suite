package xyz.lilsus.raylsuite.feature.paymentui

import android.content.Context
import androidx.annotation.StringRes

fun PaymentToastMessage.localizedMessage(context: Context): String =
    context.getString(textKey().androidStringResource())

@StringRes
fun PaymentUiTextKey.androidStringResource(): Int = when (this) {
    PaymentUiTextKey.ErrorExchangeRateUnavailable ->
        R.string.error_exchange_rate_unavailable

    PaymentUiTextKey.ErrorInvalidInvoice -> R.string.error_invalid_invoice

    PaymentUiTextKey.ErrorInvalidInvoiceWithDetails ->
        R.string.error_invalid_invoice_with_details

    PaymentUiTextKey.ErrorLnurl -> R.string.error_lnurl

    PaymentUiTextKey.ErrorLnurlWithDetails -> R.string.error_lnurl_with_details

    PaymentUiTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

    PaymentUiTextKey.ErrorUnexpectedGeneric -> R.string.error_unexpected_generic

    PaymentUiTextKey.ErrorUnexpectedWithDetails -> R.string.error_unexpected_with_details

    PaymentUiTextKey.ToastBitcoinAddress -> R.string.toast_bitcoin_address

    PaymentUiTextKey.ToastBolt12NotSupported -> R.string.toast_bolt12_not_supported

    PaymentUiTextKey.ToastLnurlRequestNotSupported ->
        R.string.toast_lnurl_request_not_supported

    PaymentUiTextKey.ToastPaymentLinkNotSupported ->
        R.string.toast_payment_link_not_supported

    PaymentUiTextKey.TransactionFee -> R.string.transaction_fee

    PaymentUiTextKey.TransactionStatusAlreadyPaid -> R.string.transaction_status_already_paid

    PaymentUiTextKey.TransactionStatusFailure -> R.string.transaction_status_failure

    PaymentUiTextKey.TransactionStatusSuccess -> R.string.transaction_status_success

    PaymentUiTextKey.WidgetNotResponding -> R.string.widget_not_responding

    PaymentUiTextKey.WidgetUnavailable -> R.string.widget_unavailable

    PaymentUiTextKey.WidgetTermsChanged -> R.string.widget_terms_changed

    PaymentUiTextKey.WidgetPriceChanged -> R.string.widget_price_changed

    PaymentUiTextKey.WidgetProductUnavailable -> R.string.widget_product_unavailable

    PaymentUiTextKey.WidgetRefused -> R.string.widget_refused

    PaymentUiTextKey.WidgetRefusedWithDetails -> R.string.widget_refused_with_details

    PaymentUiTextKey.WidgetInvalidInvoice -> R.string.widget_invalid_invoice

    PaymentUiTextKey.WidgetPriceMismatch -> R.string.widget_price_mismatch

    PaymentUiTextKey.WidgetPriceUnverifiable -> R.string.widget_price_unverifiable

    PaymentUiTextKey.WidgetBlocked -> R.string.widget_blocked

    PaymentUiTextKey.WidgetPriceHigher -> R.string.widget_price_higher

    PaymentUiTextKey.WidgetPriceLower -> R.string.widget_price_lower

    PaymentUiTextKey.DeliveryCheckingTitle -> R.string.delivery_checking_title

    PaymentUiTextKey.DeliveryCheckingBody -> R.string.delivery_checking_body

    PaymentUiTextKey.DeliveryProcessingTitle -> R.string.delivery_processing_title

    PaymentUiTextKey.DeliveryProcessingBody -> R.string.delivery_processing_body

    PaymentUiTextKey.DeliveryDeliveredTitle -> R.string.delivery_delivered_title

    PaymentUiTextKey.DeliveryFailedTitle -> R.string.delivery_failed_title

    PaymentUiTextKey.DeliveryFailedBody -> R.string.delivery_failed_body

    PaymentUiTextKey.DeliveryLostBody -> R.string.delivery_lost_body

    PaymentUiTextKey.DeliveryOrder -> R.string.delivery_order

    PaymentUiTextKey.DeliveryCloseHint -> R.string.delivery_close_hint

    PaymentUiTextKey.DeliveryCopy -> R.string.delivery_copy

    PaymentUiTextKey.DeliveryDone -> R.string.delivery_done

    PaymentUiTextKey.WidgetNothingDue -> R.string.widget_nothing_due

    PaymentUiTextKey.WidgetNothingDueWithDetails -> R.string.widget_nothing_due_with_details

    PaymentUiTextKey.WidgetUncertain -> R.string.widget_uncertain
}
