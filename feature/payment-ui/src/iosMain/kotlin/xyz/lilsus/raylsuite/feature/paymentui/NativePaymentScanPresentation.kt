package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymenthub.host.HubSavePrompt
import xyz.lilsus.raylsuite.feature.paymenthub.host.toNativePresentation
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState
import xyz.lilsus.raylsuite.feature.paymentui.amount.RangeStatus

data class NativePaymentScanSnapshot(
    val heroPhase: String,
    val receiptPreimage: String?,
    val content: NativePaymentScanContent,
    val sheet: NativePaymentScanSheet?,
    val recent: NativePaymentScanRecentEntry?,
    val cameraPermission: NativeCameraPermissionContent?
)

data class NativeCameraPermissionContent(
    val title: String,
    val body: String,
    val openSettingsTitle: String?
)

/**
 * The Scan screen's way into this session's payments. Only a product without a Recent tab supplies
 * it, and only once the session has a payment to look back at.
 */
data class NativePaymentScanRecentEntry(
    val title: String,
    val newTransactionCount: Int,
    val transactionCount: Int
)

data class NativePaymentScanContent(
    val kind: String,
    val title: String,
    val subtitle: String?,
    val primaryAmount: String?,
    val secondaryText: String?,
    val feeHint: String?,
    val actionTitle: String?,
    val tapToContinue: String?
)

data class NativePaymentScanSheet(
    val kind: String,
    val title: String,
    val body: String?,
    val amount: String?,
    val exactAmount: String?,
    val fundingSource: String?,
    val recipientTitle: String?,
    val recipientDescription: String?,
    val recipientImageBase64: String?,
    val currencyLabel: String?,
    val rangeMessage: String?,
    val allowsDecimal: Boolean,
    val canSubmit: Boolean,
    val primaryAction: String,
    val primaryActionTitle: String,
    val secondaryActionTitle: String?,
    val tertiaryActionTitle: String?,
    val textFieldLabel: String?,
    val textFieldValue: String?,
    val manualReview: NativeManualAmountReview? = null,
    val currencySwitch: NativeManualCurrencySwitch? = null
)

data class NativeManualCurrencySwitch(
    val title: String,
    val currency: String,
    val equivalent: String?
)

data class NativeManualAmountReview(
    val title: String,
    val amount: String?,
    val payTitle: String,
    val editTitle: String
)

/**
 * Converts provider-neutral payment presentation into values consumed by SwiftUI.
 * Provider state and errors must be projected before crossing this boundary.
 */
suspend fun nativePaymentScanSnapshot(
    payment: PaymentScreenState,
    appTitle: String,
    estimatedFeeHint: String?,
    previousPaymentSituation: PreviousPaymentSituation?,
    savePrompt: HubSavePrompt?,
    receiptVisible: Boolean,
    canOpenPreviousPayment: Boolean = true,
    showResolvingContent: Boolean = true,
    recentCount: Int = 0,
    newRecentCount: Int = 0,
    offersRecentEntryPoint: Boolean = false,
    cameraAuthorization: CameraAuthorizationState = CameraAuthorizationState.AUTHORIZED,
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentScanSnapshot {
    val receiptPreimage =
        (payment as? PaymentScreenState.Success)
            ?.preimage
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    return NativePaymentScanSnapshot(
        heroPhase = payment.toNativeHeroPhaseValue(),
        receiptPreimage = receiptPreimage.takeIf { receiptVisible },
        content =
            payment.toNativeContent(
                appTitle = appTitle,
                estimatedFeeHint = estimatedFeeHint,
                receiptVisible = receiptVisible && receiptPreimage != null,
                showResolvingContent = showResolvingContent,
                formatter = formatter
            ),
        sheet =
            savePrompt?.toNativeSheet()
                ?: payment.toNativeSheet(
                    previousPaymentSituation,
                    canOpenPreviousPayment,
                    formatter
                ),
        recent =
            if (offersRecentEntryPoint && recentCount > 0) {
                NativePaymentScanRecentEntry(
                    title = nativeString(
                        NativeStringResource(table = "PaymentUI", key = "view_session_transactions")
                    ),
                    newTransactionCount = newRecentCount,
                    transactionCount = recentCount
                )
            } else {
                null
            },
        cameraPermission =
            if (payment == PaymentScreenState.Active) {
                cameraAuthorization.toNativePermissionContent()
            } else {
                null
            }
    )
}

private suspend fun CameraAuthorizationState.toNativePermissionContent():
    NativeCameraPermissionContent? =
    when (this) {
        CameraAuthorizationState.DENIED ->
            NativeCameraPermissionContent(
                title = nativeString(
                    NativeStringResource(
                        table = "PaymentUI",
                        key = "camera_permission_denied_title"
                    )
                ),
                body = nativeString(
                    NativeStringResource(table = "PaymentUI", key = "camera_permission_denied_body")
                ),
                openSettingsTitle = nativeString(
                    NativeStringResource(
                        table = "PaymentUI",
                        key = "camera_permission_open_settings"
                    )
                )
            )

        CameraAuthorizationState.RESTRICTED,
        CameraAuthorizationState.UNAVAILABLE ->
            NativeCameraPermissionContent(
                title = nativeString(
                    NativeStringResource(
                        table = "PaymentUI",
                        key = "camera_permission_restricted_title"
                    )
                ),
                body = nativeString(
                    NativeStringResource(
                        table = "PaymentUI",
                        key = "camera_permission_restricted_body"
                    )
                ),
                openSettingsTitle = null
            )

        CameraAuthorizationState.NOT_DETERMINED,
        CameraAuthorizationState.AUTHORIZED -> null
    }

private suspend fun PaymentScreenState.toNativeContent(
    appTitle: String,
    estimatedFeeHint: String?,
    receiptVisible: Boolean,
    showResolvingContent: Boolean,
    formatter: AmountFormatter
): NativePaymentScanContent = when (this) {
    is PaymentScreenState.Loading ->
        if (kind == PaymentLoadingKind.Resolving && showResolvingContent) {
            NativePaymentScanContent(
                kind = CONTENT_RESOLVING,
                title = nativeString(
                    NativeStringResource(table = "PaymentUI", key = "resolving_payment_title")
                ),
                subtitle = nativeString(
                    NativeStringResource(table = "PaymentUI", key = "resolving_payment_subtitle")
                ),
                primaryAmount = null,
                secondaryText = null,
                feeHint = null,
                actionTitle = null,
                tapToContinue = null
            )
        } else {
            idleContent(appTitle)
        }

    is PaymentScreenState.Success ->
        when {
            receiptVisible -> receiptContent()
            wasAlreadyPaid -> alreadyPaidContent(preimage)
            else -> successContent(formatter, estimatedFeeHint)
        }

    is PaymentScreenState.Error ->
        NativePaymentScanContent(
            kind = CONTENT_ERROR,
            title = nativeString(
                NativeStringResource(table = "PaymentUI", key = "result_error_title")
            ),
            subtitle = message,
            primaryAmount = null,
            secondaryText = null,
            feeHint = null,
            actionTitle = null,
            tapToContinue = nativeString(
                NativeStringResource(table = "PaymentUI", key = "tap_continue")
            )
        )

    else -> idleContent(appTitle)
}

internal suspend fun PaymentScreenState.toNativeRecentDetailContent(
    estimatedFeeHint: String?,
    receiptVisible: Boolean,
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentScanContent = toNativeContent(
    appTitle = "",
    estimatedFeeHint = estimatedFeeHint,
    receiptVisible = receiptVisible,
    showResolvingContent = true,
    formatter = formatter
)

private suspend fun idleContent(appTitle: String) = NativePaymentScanContent(
    kind = CONTENT_IDLE,
    title = appTitle,
    subtitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "point_camera_message_subtitle")
    ),
    primaryAmount = null,
    secondaryText = null,
    feeHint = null,
    actionTitle = null,
    tapToContinue = null
)

private suspend fun PaymentScreenState.Success.successContent(
    formatter: AmountFormatter,
    estimatedFeeHint: String?
) = NativePaymentScanContent(
    kind = CONTENT_SUCCESS,
    title = nativeString(NativeStringResource(table = "PaymentUI", key = "result_paid_title")),
    subtitle = null,
    primaryAmount = formatter.format(amountPaid),
    secondaryText = nativeString(
        NativeStringResource(table = "PaymentUI", key = "result_paid_fee"),
        formatter.format(feePaid)
    ),
    feeHint = estimatedFeeHint.takeIf { showEstimatedFeeHint },
    actionTitle =
        nativeString(NativeStringResource(table = "PaymentUI", key = "result_view_receipt"))
            .takeIf { !preimage.isNullOrBlank() },
    tapToContinue = nativeString(NativeStringResource(table = "PaymentUI", key = "tap_continue"))
)

private suspend fun alreadyPaidContent(preimage: String?) = NativePaymentScanContent(
    kind = CONTENT_ALREADY_PAID,
    title = nativeString(
        NativeStringResource(table = "PaymentUI", key = "result_already_paid_title")
    ),
    subtitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "result_already_paid_message")
    ),
    primaryAmount = null,
    secondaryText = null,
    feeHint = null,
    actionTitle =
        nativeString(NativeStringResource(table = "PaymentUI", key = "result_view_receipt"))
            .takeIf { !preimage.isNullOrBlank() },
    tapToContinue = nativeString(NativeStringResource(table = "PaymentUI", key = "tap_continue"))
)

private suspend fun receiptContent() = NativePaymentScanContent(
    kind = CONTENT_RECEIPT,
    title = nativeString(NativeStringResource(table = "PaymentUI", key = "result_receipt_title")),
    subtitle =
        nativeString(
            NativeStringResource(table = "PaymentUI", key = "result_receipt_body_prefix")
        ) +
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "result_receipt_body_preimage")
            ) +
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "result_receipt_body_middle")
            ) +
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "result_receipt_body_only")
            ) +
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "result_receipt_body_suffix")
            ),
    primaryAmount = null,
    secondaryText = null,
    feeHint = null,
    actionTitle = null,
    tapToContinue = nativeString(NativeStringResource(table = "PaymentUI", key = "tap_continue"))
)

private suspend fun PaymentScreenState.toNativeSheet(
    previousPaymentSituation: PreviousPaymentSituation?,
    canOpenPreviousPayment: Boolean,
    formatter: AmountFormatter
): NativePaymentScanSheet? = when (this) {
    is PaymentScreenState.EnterAmount -> entry.toNativeSheet(lnurlPayDisplay, formatter)

    is PaymentScreenState.Confirm -> toNativeSheet(formatter)

    is PaymentScreenState.PendingRetry ->
        (previousPaymentSituation ?: PreviousPaymentSituation.InProgress).toNativeSheet(
            canOpenPreviousPayment
        )

    else -> null
}

private suspend fun ManualAmountUiState.toNativeSheet(
    recipient: LnurlPayDisplay?,
    formatter: AmountFormatter
): NativePaymentScanSheet = NativePaymentScanSheet(
    kind = SHEET_MANUAL_AMOUNT,
    title = nativeString(NativeStringResource(table = "PaymentUI", key = "enter_amount_title")),
    body = null,
    amount = committedNumber(),
    exactAmount = null,
    fundingSource = null,
    recipientTitle = recipient?.recipientTitle(),
    recipientDescription = recipient?.description,
    recipientImageBase64 = recipient?.image?.encodedBase64(),
    currencyLabel = currency.nativeLabel(),
    rangeMessage = rangeStatus.message(),
    allowsDecimal = allowDecimal,
    canSubmit = amount != null && amount.minor > 0L && rangeStatus == RangeStatus.InRange,
    primaryAction = "submit",
    primaryActionTitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "continue_button")
    ),
    secondaryActionTitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "dismiss_button")
    ),
    tertiaryActionTitle = null,
    textFieldLabel = null,
    textFieldValue = null,
    currencySwitch = alternativeCurrency?.let { alternative ->
        NativeManualCurrencySwitch(
            title = nativeString(
                NativeStringResource(table = "PaymentUI", key = "enter_amount_switch_currency"),
                alternative.nativeLabel().uppercase()
            ),
            currency = alternative.nativeLabel().uppercase(),
            equivalent = equivalentAmount?.let { "≈ ${formatter.format(it)}" }
        )
    },
    manualReview = NativeManualAmountReview(
        title = nativeString(
            NativeStringResource(table = "PaymentUI", key = "confirm_payment_title")
        ),
        amount = amount?.let(formatter::format),
        payTitle = nativeString(NativeStringResource(table = "PaymentUI", key = "pay_button")),
        editTitle = nativeString(
            NativeStringResource(table = "PaymentUI", key = "edit_amount_button")
        )
    )
)

private suspend fun PaymentScreenState.Confirm.toNativeSheet(
    formatter: AmountFormatter
): NativePaymentScanSheet = NativePaymentScanSheet(
    kind = SHEET_CONFIRMATION,
    title = nativeString(NativeStringResource(table = "PaymentUI", key = "confirm_payment_title")),
    body = null,
    amount =
        if (amount.primaryIsEstimate) {
            nativeString(
                NativeStringResource(
                    table = "PaymentUI",
                    key = "confirm_payment_approximate_amount"
                ),
                formatter.format(amount.primary)
            )
        } else {
            formatter.format(amount.primary)
        },
    exactAmount =
        amount.exactSats?.let {
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "confirm_payment_exact_amount"),
                formatter.format(it)
            )
        },
    fundingSource = fundingSource,
    recipientTitle = lnurlPayDisplay?.recipientTitle(),
    recipientDescription = lnurlPayDisplay?.description,
    recipientImageBase64 = lnurlPayDisplay?.image?.encodedBase64(),
    currencyLabel = null,
    rangeMessage = null,
    allowsDecimal = false,
    canSubmit = true,
    primaryAction = "submit",
    primaryActionTitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "pay_button")
    ),
    secondaryActionTitle = nativeString(
        NativeStringResource(table = "PaymentUI", key = "dismiss_button")
    ),
    tertiaryActionTitle = null,
    textFieldLabel = null,
    textFieldValue = null
)

private suspend fun PreviousPaymentSituation.toNativeSheet(
    canOpenPreviousPayment: Boolean
): NativePaymentScanSheet {
    val canRetry = this == PreviousPaymentSituation.OutcomeUnknown
    val (title, body) =
        when (this) {
            PreviousPaymentSituation.InProgress ->
                NativeStringResource(table = "PaymentUI", key = "in_progress_title") to
                    NativeStringResource(table = "PaymentUI", key = "in_progress_body")

            PreviousPaymentSituation.OutcomeUnknown ->
                NativeStringResource(table = "PaymentUI", key = "outcome_unknown_title") to
                    NativeStringResource(table = "PaymentUI", key = "outcome_unknown_body")

            PreviousPaymentSituation.Completed ->
                NativeStringResource(table = "PaymentUI", key = "completed_title") to
                    NativeStringResource(table = "PaymentUI", key = "completed_body")
        }
    return NativePaymentScanSheet(
        kind = SHEET_REPEAT_PAYMENT,
        title = nativeString(title),
        body = nativeString(body),
        amount = null,
        exactAmount = null,
        fundingSource = null,
        recipientTitle = null,
        recipientDescription = null,
        recipientImageBase64 = null,
        currencyLabel = null,
        rangeMessage = null,
        allowsDecimal = false,
        canSubmit = true,
        primaryAction =
            when {
                canRetry -> "retry"
                canOpenPreviousPayment -> "view"
                else -> "additional"
            },
        primaryActionTitle =
            nativeString(
                when {
                    canRetry -> NativeStringResource(
                        table = "PaymentUI",
                        key = "retry_previous_invoice"
                    )

                    canOpenPreviousPayment -> NativeStringResource(
                        table = "PaymentUI",
                        key = "view_previous_payment"
                    )

                    else -> NativeStringResource(
                        table = "PaymentUI",
                        key = "create_additional_payment"
                    )
                }
            ),
        secondaryActionTitle =
            nativeString(
                NativeStringResource(table = "PaymentUI", key = "create_additional_payment")
            )
                .takeIf { canRetry || canOpenPreviousPayment },
        tertiaryActionTitle =
            nativeString(NativeStringResource(table = "PaymentUI", key = "view_previous_payment"))
                .takeIf { canRetry && canOpenPreviousPayment },
        textFieldLabel = null,
        textFieldValue = null
    )
}

private suspend fun HubSavePrompt.toNativeSheet(): NativePaymentScanSheet {
    val presentation = toNativePresentation()
    return NativePaymentScanSheet(
        kind = SHEET_SAVE_TARGET,
        title = presentation.title,
        body = presentation.body,
        amount = null,
        exactAmount = null,
        fundingSource = null,
        recipientTitle = null,
        recipientDescription = null,
        recipientImageBase64 = null,
        currencyLabel = null,
        rangeMessage = null,
        allowsDecimal = false,
        canSubmit = true,
        primaryAction = "save",
        primaryActionTitle = presentation.saveTitle,
        secondaryActionTitle = presentation.dismissTitle,
        tertiaryActionTitle = null,
        textFieldLabel = presentation.nameLabel,
        textFieldValue = presentation.targetName
    )
}

private suspend fun LnurlPayDisplay.recipientTitle(): String =
    nativeString(NativeStringResource(table = "PaymentUI", key = "lnurl_payment_recipient"), domain)

private suspend fun RangeStatus.message(): String? = when (this) {
    RangeStatus.InRange,
    RangeStatus.Unknown -> null

    RangeStatus.BelowMin ->
        nativeString(NativeStringResource(table = "PaymentUI", key = "enter_amount_too_low"))

    RangeStatus.AboveMax ->
        nativeString(NativeStringResource(table = "PaymentUI", key = "enter_amount_too_large"))
}

private fun ManualAmountUiState.committedNumber(): String {
    val normalized = rawWhole.ifEmpty { "0" }
    val whole =
        if (normalized.length <= 3) {
            normalized
        } else {
            normalized.reversed().chunked(3).joinToString(",").reversed()
        }
    if (!allowDecimal || (!hasDecimal && rawFraction.isEmpty())) return whole
    return "$whole.${rawFraction.ifEmpty { "0" }}"
}

private fun DisplayCurrency.nativeLabel(): String = when (this) {
    DisplayCurrency.Satoshi -> "sat"
    is DisplayCurrency.Fiat -> iso4217.uppercase()
}

private const val CONTENT_IDLE = "idle"
private const val CONTENT_RESOLVING = "resolving"
private const val CONTENT_SUCCESS = "success"
private const val CONTENT_ALREADY_PAID = "alreadyPaid"
private const val CONTENT_RECEIPT = "receipt"
private const val CONTENT_ERROR = "error"

private const val SHEET_MANUAL_AMOUNT = "manualAmount"
private const val SHEET_CONFIRMATION = "confirmation"
private const val SHEET_REPEAT_PAYMENT = "repeatPayment"
private const val SHEET_SAVE_TARGET = "saveTarget"
