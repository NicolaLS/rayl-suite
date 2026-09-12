package xyz.lilsus.raylsuite.feature.paymentui.amount

import kotlin.math.pow
import kotlin.math.roundToLong
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

data class ManualAmountConfig(
    val info: CurrencyInfo,
    val exchangeRate: Double?,
    val min: DisplayAmount? = null,
    val max: DisplayAmount? = null,
    val minMsats: Long? = null,
    val maxMsats: Long? = null
)

class ManualAmountController(private val defaultConfig: ManualAmountConfig) {
    private var config: ManualAmountConfig = defaultConfig
    private var whole: String = "0"
    private var fraction: String = ""
    private var hasDecimal: Boolean = false

    private var state: ManualAmountUiState = ManualAmountUiState(
        amount = null,
        currency = defaultConfig.info.currency,
        min = defaultConfig.min,
        max = defaultConfig.max,
        allowDecimal = defaultConfig.info.fractionDigits > 0,
        rawWhole = whole,
        rawFraction = fraction,
        hasDecimal = hasDecimal,
        rangeStatus = RangeStatus.Unknown
    )

    fun reset(
        config: ManualAmountConfig = defaultConfig,
        clearInput: Boolean = true
    ): ManualAmountUiState {
        this.config = config
        if (clearInput) {
            whole = "0"
            fraction = ""
            hasDecimal = false
        } else if (config.info.fractionDigits == 0) {
            hasDecimal = false
            fraction = ""
        }
        state = ManualAmountUiState(
            amount = null,
            currency = config.info.currency,
            min = config.min,
            max = config.max,
            allowDecimal = config.info.fractionDigits > 0,
            rawWhole = whole,
            rawFraction = fraction,
            hasDecimal = hasDecimal,
            rangeStatus = RangeStatus.Unknown
        )
        updateState()
        return state
    }

    fun current(): ManualAmountUiState = state

    fun presetAmount(amount: DisplayAmount): ManualAmountUiState {
        if (amount.currency != config.info.currency) return state
        val fractionDigits = config.info.fractionDigits
        val divisor = 10.0.pow(fractionDigits).toLong().coerceAtLeast(1L)
        val wholePart = amount.minor / divisor
        val fractionPart = amount.minor % divisor
        whole = wholePart.toString()
        fraction = if (fractionDigits == 0) {
            ""
        } else {
            fractionPart.toString().padStart(fractionDigits, '0').trimEnd('0')
        }
        hasDecimal = fractionDigits > 0 && fraction.isNotEmpty()
        updateState()
        return state
    }

    fun handleKeyPress(key: ManualAmountKey): ManualAmountUiState {
        when (key) {
            is ManualAmountKey.Digit -> appendDigit(key.value)
            ManualAmountKey.Decimal -> insertDecimal()
            ManualAmountKey.Backspace -> backspace()
        }
        return state
    }

    fun enteredAmount(): DisplayAmount? = state.amount

    private fun appendDigit(digit: Int) {
        require(digit in 0..9)
        if (!hasDecimal) {
            if (whole == "0") {
                whole = digit.toString()
            } else {
                if (whole.length >= MAX_WHOLE_DIGITS) return
                whole += digit
            }
        } else {
            if (fraction.length >= config.info.fractionDigits) return
            fraction += digit
        }
        updateState()
    }

    private fun insertDecimal() {
        if (!state.allowDecimal) return
        if (hasDecimal) return
        hasDecimal = true
        updateState()
    }

    private fun backspace() {
        if (hasDecimal && fraction.isNotEmpty()) {
            fraction = fraction.dropLast(1)
        } else if (hasDecimal) {
            hasDecimal = false
        } else {
            whole = whole.dropLast(1)
            if (whole.isEmpty()) {
                whole = "0"
            }
        }
        updateState()
    }

    private fun updateState() {
        whole = normalizeWhole(whole)
        if (!hasDecimal) fraction = ""
        fraction = normalizeFraction(fraction, config.info.fractionDigits)

        val minor = parseMinor(whole, fraction, config.info.fractionDigits)
        val amount = minor?.takeIf { it > 0L }?.let {
            DisplayAmount(it, config.info.currency)
        }
        val msats = minor?.takeIf { it > 0L }?.let { minorToMsats(it) }
        val minMsats = config.minMsats
        val maxMsats = config.maxMsats
        val minDisplay = config.min
        val maxDisplay = config.max
        val rangeStatus = when {
            msats == null -> RangeStatus.Unknown

            minMsats != null && minDisplay != null && msats < minMsats -> RangeStatus.BelowMin(
                minDisplay
            )

            maxMsats != null && maxDisplay != null && msats > maxMsats -> RangeStatus.AboveMax(
                maxDisplay
            )

            else -> RangeStatus.InRange
        }
        state = state.copy(
            amount = amount,
            currency = config.info.currency,
            min = config.min,
            max = config.max,
            allowDecimal = config.info.fractionDigits > 0,
            rawWhole = whole,
            rawFraction = fraction,
            hasDecimal = hasDecimal,
            rangeStatus = rangeStatus
        )
    }

    private fun normalizeWhole(raw: String): String {
        val cleaned = raw.dropWhile { it == '0' }
        return cleaned.ifEmpty { "0" }
    }

    private fun normalizeFraction(raw: String, fractionDigits: Int): String {
        if (!hasDecimal || fractionDigits == 0) return ""
        return raw.take(fractionDigits)
    }

    private fun parseMinor(whole: String, fraction: String, fractionDigits: Int): Long? {
        val paddedFraction = if (fractionDigits == 0) "" else fraction.padEnd(fractionDigits, '0')
        val digits = (whole + paddedFraction).trimStart('0')
        val normalized = digits.ifEmpty { "0" }
        return normalized.toLongOrNull()
    }

    private fun minorToMsats(minor: Long): Long? {
        val info = config.info
        return when (info.currency) {
            DisplayCurrency.Satoshi -> minor * MSATS_PER_SAT

            is DisplayCurrency.Fiat -> {
                val rate = config.exchangeRate ?: return null
                val major = minor.toDouble() / 10.0.pow(info.fractionDigits)
                val btc = major / rate
                val msats = (btc * MSATS_PER_BTC).roundToLong()
                if (msats <= 0) null else msats
            }
        }
    }

    companion object {
        private const val MAX_WHOLE_DIGITS = 12
        private const val MSATS_PER_SAT = 1_000L
        private const val MSATS_PER_BTC = 100_000_000_000L
    }
}
