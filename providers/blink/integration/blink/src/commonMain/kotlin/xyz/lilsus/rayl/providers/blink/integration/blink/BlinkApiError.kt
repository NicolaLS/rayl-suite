package xyz.lilsus.rayl.providers.blink.integration.blink

/**
 * Blink-specific error types that can be translated to localized messages.
 */
enum class BlinkErrorType {
    PermissionDenied,
    InsufficientBalance,
    RouteNotFound,
    InvoiceExpired,
    SelfPayment,
    InvalidInvoice,
    AmountTooSmall,
    LimitExceeded,
    RateLimited,
    InvalidApiKey
}

/**
 * Errors emitted by the Blink API boundary.
 */
sealed class BlinkApiError {
    data object MissingWalletConnection : BlinkApiError()

    data object FundingWalletUnavailable : BlinkApiError()

    data class PaymentRejected(val code: String? = null, val message: String? = null) :
        BlinkApiError()

    data object NetworkUnavailable : BlinkApiError()

    data object Timeout : BlinkApiError()

    data class Unexpected(val message: String? = null) : BlinkApiError()

    data class BlinkError(val type: BlinkErrorType) : BlinkApiError()
}

class BlinkApiException(val error: BlinkApiError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
