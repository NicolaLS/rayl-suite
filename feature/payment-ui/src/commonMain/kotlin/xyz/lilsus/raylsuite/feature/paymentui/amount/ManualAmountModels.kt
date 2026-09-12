package xyz.lilsus.raylsuite.feature.paymentui.amount

import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

data class ManualAmountUiState(
    val amount: DisplayAmount?,
    val currency: DisplayCurrency,
    val allowDecimal: Boolean = true,
    val rawWhole: String = "0",
    val rawFraction: String = "",
    val hasDecimal: Boolean = false,
    val rangeStatus: RangeStatus = RangeStatus.InRange,
    val alternativeCurrency: DisplayCurrency? = null,
    val equivalentAmount: DisplayAmount? = null
)

sealed interface RangeStatus {
    data object InRange : RangeStatus

    data object Unknown : RangeStatus

    data object BelowMin : RangeStatus

    data object AboveMax : RangeStatus
}

sealed interface ManualAmountKey {
    data class Digit(val value: Int) : ManualAmountKey

    data object Decimal : ManualAmountKey

    data object Backspace : ManualAmountKey

    data object SwitchCurrency : ManualAmountKey
}
