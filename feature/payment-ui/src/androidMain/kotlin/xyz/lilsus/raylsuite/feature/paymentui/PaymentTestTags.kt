package xyz.lilsus.raylsuite.feature.paymentui

object PaymentTestTags {
    const val SCREEN = "payment_screen"
    const val ACTIVE_CONTENT = "payment_active_content"
    const val SETTINGS_BUTTON = "payment_settings_button"
    const val SESSION_TRANSACTIONS_BUTTON = "payment_session_transactions_button"
    const val SCANNER_CLOSE_BUTTON = "payment_scanner_close_button"

    const val CONFIRMATION_SHEET = "payment_confirmation_sheet"
    const val CONFIRMATION_PAY_BUTTON = "payment_confirmation_pay_button"
    const val CONFIRMATION_DISMISS_BUTTON = "payment_confirmation_dismiss_button"
    const val LNURL_PAY_DETAILS = "payment_lnurl_pay_details"

    const val MANUAL_AMOUNT_SHEET = "payment_manual_amount_sheet"
    const val MANUAL_AMOUNT_DISPLAY = "payment_manual_amount_display"
    const val MANUAL_AMOUNT_CONTINUE_BUTTON = "payment_manual_amount_continue_button"
    const val MANUAL_AMOUNT_REVIEW = "payment_manual_amount_review"
    const val MANUAL_AMOUNT_EDIT_BUTTON = "payment_manual_amount_edit_button"
    const val MANUAL_AMOUNT_PAY_BUTTON = "payment_manual_amount_pay_button"
    const val MANUAL_AMOUNT_DECIMAL_KEY = "payment_manual_amount_key_decimal"
    const val MANUAL_AMOUNT_BACKSPACE_KEY = "payment_manual_amount_key_backspace"

    const val RESULT = "payment_result"
    const val RESULT_SUCCESS = "payment_result_success"
    const val RESULT_ALREADY_PAID = "payment_result_already_paid"
    const val RESULT_ERROR = "payment_result_error"
    const val RESULT_VIEW_RECEIPT = "payment_result_view_receipt"

    fun manualAmountDigitKey(value: Int): String = "payment_manual_amount_key_$value"
}
