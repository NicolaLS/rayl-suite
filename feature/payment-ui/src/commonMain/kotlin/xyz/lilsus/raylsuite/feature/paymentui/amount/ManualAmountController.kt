package xyz.lilsus.raylsuite.feature.paymentui.amount

import kotlin.math.pow
import kotlin.math.roundToLong
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount

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
    private var enteringSats = false
    private var alternateInput: Input? = null
    private val activeInfo: CurrencyInfo
        get() = if (enteringSats) CurrencyCatalog.infoFor("SAT") else config.info
    private val alternativeInfo: CurrencyInfo?
        get() = if (config.info.currency is DisplayCurrency.Fiat) {
            if (enteringSats) config.info else CurrencyCatalog.infoFor("SAT")
        } else {
            null
        }

    private data class Input(val whole: String, val fraction: String, val hasDecimal: Boolean)

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

    init {
        updateState()
    }

    fun reset(
        config: ManualAmountConfig = defaultConfig,
        clearInput: Boolean = true
    ): ManualAmountUiState {
        val currencyChanged = this.config.info.currency != config.info.currency
        this.config = config
        if (clearInput || currencyChanged) {
            enteringSats = false
            alternateInput = null
            whole = "0"
            fraction = ""
            hasDecimal = false
        } else if (activeInfo.fractionDigits == 0) {
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
        if (amount.currency != activeInfo.currency) return state
        alternateInput = null
        val fractionDigits = activeInfo.fractionDigits
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
        if (key != ManualAmountKey.SwitchCurrency) alternateInput = null
        when (key) {
            is ManualAmountKey.Digit -> appendDigit(key.value)
            ManualAmountKey.Decimal -> insertDecimal()
            ManualAmountKey.Backspace -> backspace()
            ManualAmountKey.SwitchCurrency -> switchCurrency()
        }
        return state
    }

    fun enteredAmount(): DisplayAmount? = state.amount

    private fun switchCurrency() {
        val target = alternativeInfo ?: return
        val previous = Input(whole, fraction, hasDecimal)
        val restored = alternateInput
        val equivalent = state.equivalentAmount
        enteringSats = !enteringSats
        if (restored != null) {
            whole = restored.whole
            fraction = restored.fraction
            hasDecimal = restored.hasDecimal
        } else {
            presetAmount(equivalent ?: DisplayAmount(0, target.currency))
        }
        // Preserve the original entry across a round trip without edits; fiat rounding is lossy.
        alternateInput = previous
        updateState()
    }

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
            if (fraction.length >= activeInfo.fractionDigits) return
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
        val info = activeInfo
        whole = normalizeWhole(whole)
        if (!hasDecimal) fraction = ""
        fraction = normalizeFraction(fraction, info.fractionDigits)

        val minor = parseMinor(whole, fraction, info.fractionDigits)
        val amount = minor?.takeIf { it > 0L }?.let {
            DisplayAmount(it, info.currency)
        }
        val msats = minor?.takeIf { it > 0L }?.let { minorToMsats(it) }
        val minMsats = config.minMsats
        val maxMsats = config.maxMsats
        val minDisplay = if (enteringSats) {
            minMsats?.let { DisplayAmount(roundedUpSats(it), info.currency) }
        } else {
            config.min
        }
        val maxDisplay = if (enteringSats) {
            maxMsats?.let { DisplayAmount(it / MSATS_PER_SAT, info.currency) }
        } else {
            config.max
        }
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
            currency = info.currency,
            min = minDisplay,
            max = maxDisplay,
            allowDecimal = info.fractionDigits > 0,
            rawWhole = whole,
            rawFraction = fraction,
            hasDecimal = hasDecimal,
            rangeStatus = rangeStatus,
            alternativeCurrency = alternativeInfo?.currency,
            equivalentAmount = alternativeInfo?.let { target ->
                if (minor == 0L) {
                    DisplayAmount(0, target.currency)
                } else {
                    msats?.let {
                        if (target.currency == DisplayCurrency.Satoshi) {
                            DisplayAmount(roundedUpSats(it), target.currency)
                        } else {
                            convertMsatsToDisplayAmount(it, target, validExchangeRate())
                        }
                    }
                }
            }
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

    private fun roundedUpSats(msats: Long): Long =
        msats / MSATS_PER_SAT + if (msats % MSATS_PER_SAT > 0) 1 else 0

    private fun validExchangeRate(): Double? =
        config.exchangeRate?.takeIf { it.isFinite() && it > 0 }

    private fun minorToMsats(minor: Long): Long? {
        val info = activeInfo
        return when (info.currency) {
            DisplayCurrency.Satoshi -> {
                if (minor > Long.MAX_VALUE / MSATS_PER_SAT) return null
                minor * MSATS_PER_SAT
            }

            is DisplayCurrency.Fiat -> {
                val rate = validExchangeRate() ?: return null
                val major = minor.toDouble() / 10.0.pow(info.fractionDigits)
                val btc = major / rate
                val msats = btc * MSATS_PER_BTC
                if (!msats.isFinite() || msats <= 0 || msats >= Long.MAX_VALUE.toDouble()) {
                    null
                } else {
                    msats.roundToLong().takeIf { it > 0 }
                }
            }
        }
    }

    companion object {
        private const val MAX_WHOLE_DIGITS = 12
        private const val MSATS_PER_SAT = 1_000L
        private const val MSATS_PER_BTC = 100_000_000_000L
    }
}
