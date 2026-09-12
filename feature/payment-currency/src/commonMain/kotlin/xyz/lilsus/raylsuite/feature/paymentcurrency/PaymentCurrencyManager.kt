package xyz.lilsus.raylsuite.feature.paymentcurrency

import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider

class PaymentCurrencyManager(
    private val bitcoinPriceProvider: BitcoinPriceProvider,
    private val scope: CoroutineScope
) {
    private val mutableState =
        MutableStateFlow(
            CurrencyState(
                info = CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE),
                exchangeRate = null
            )
        )
    val state: StateFlow<CurrencyState> = mutableState.asStateFlow()

    private val mutableErrors = MutableSharedFlow<CurrencyManagerError>(extraBufferCapacity = 4)
    val errors: SharedFlow<CurrencyManagerError> = mutableErrors.asSharedFlow()

    private var exchangeRateJob: Job? = null
    private var exchangeRateRequestId: Int = 0

    fun setPreferredCurrency(currency: DisplayCurrency) {
        ensureExchangeRateIfNeeded(CurrencyCatalog.infoFor(currency))
    }

    fun ensureExchangeRateIfNeeded(info: CurrencyInfo = mutableState.value.info) {
        if (info.currency !is DisplayCurrency.Fiat) {
            invalidateExchangeRateJob()
            mutableState.value = mutableState.value.copy(exchangeRate = null, info = info)
            return
        }
        fetchExchangeRate(info)
    }

    fun convertMsatsToDisplay(msats: Long): DisplayAmount =
        convertMsatsToDisplay(msats, mutableState.value)

    fun convertMsatsToDisplay(msats: Long, currencyState: CurrencyState): DisplayAmount =
        convertMsatsToDisplayAmount(
            msats = msats,
            info = currencyState.info,
            fiatPricePerBitcoin = currencyState.exchangeRate
        ) ?: DisplayAmount(msats / MSATS_PER_SAT, DisplayCurrency.Satoshi)

    /** Quotes a stored display-currency amount with a fresh exchange rate when it is fiat. */
    suspend fun quoteStoredAmount(amount: StoredAmount): PaymentAmountQuote? {
        val code = amount.normalizedCurrencyCode
        if (code !in CurrencyCatalog.supportedCodes || amount.minor <= 0L) return null
        val info = CurrencyCatalog.infoFor(code)
        return quote(DisplayAmount(amount.minor, info.currency))
    }

    suspend fun quote(amount: DisplayAmount): PaymentAmountQuote? {
        if (amount.minor <= 0L) return null
        val info = when (val currency = amount.currency) {
            DisplayCurrency.Satoshi -> CurrencyCatalog.infoFor("SAT")

            is DisplayCurrency.Fiat -> {
                val code = currency.iso4217.trim().uppercase()
                if (code !in CurrencyCatalog.supportedCodes) return null
                CurrencyCatalog.infoFor(code)
            }
        }
        val rate = when (info.currency) {
            DisplayCurrency.Satoshi -> null
            is DisplayCurrency.Fiat -> freshExchangeRate(info) ?: return null
        }
        val amountMsats = amount.toRoundedMsats(info, rate) ?: return null
        return PaymentAmountQuote(
            requestedAmount = amount,
            amountMsats = amountMsats,
            exchangeRate = rate
        )
    }

    suspend fun convertMsatsToFreshDisplay(msats: Long): DisplayAmount {
        val info = mutableState.value.info
        if (info.currency !is DisplayCurrency.Fiat) {
            return convertMsatsToDisplay(
                msats,
                CurrencyState(info = info, exchangeRate = null)
            )
        }
        val rate = freshExchangeRate(info)
            ?: return DisplayAmount(msats / MSATS_PER_SAT, DisplayCurrency.Satoshi)
        return convertMsatsToDisplay(
            msats,
            CurrencyState(info = info, exchangeRate = rate)
        )
    }

    private fun fetchExchangeRate(info: CurrencyInfo) {
        invalidateExchangeRateJob()
        val current = mutableState.value
        mutableState.value =
            if (current.info.code.equals(info.code, ignoreCase = true)) {
                current.copy(info = info)
            } else {
                CurrencyState(info = info, exchangeRate = null)
            }
        val requestId = exchangeRateRequestId
        exchangeRateJob =
            scope.launch {
                val price =
                    bitcoinPriceProvider
                        .pricePerBitcoin(info.code)
                        ?.takeIf { it.isFinite() && it > 0.0 }
                if (requestId != exchangeRateRequestId) return@launch
                if (price == null) {
                    mutableErrors.tryEmit(CurrencyManagerError.ExchangeRateUnavailable(info.code))
                    return@launch
                }
                mutableState.value =
                    CurrencyState(
                        info = info,
                        exchangeRate = price
                    )
            }
    }

    private suspend fun freshExchangeRate(info: CurrencyInfo): Double? {
        val price =
            bitcoinPriceProvider
                .pricePerBitcoin(info.code)
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: return null
        if (mutableState.value.info.code.equals(info.code, ignoreCase = true)) {
            mutableState.value = CurrencyState(info = info, exchangeRate = price)
        }
        return price
    }

    private fun invalidateExchangeRateJob() {
        exchangeRateJob?.cancel()
        exchangeRateJob = null
        exchangeRateRequestId += 1
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}

data class CurrencyState(val info: CurrencyInfo, val exchangeRate: Double?)

data class PaymentAmountQuote(
    val requestedAmount: DisplayAmount,
    val amountMsats: Long,
    val exchangeRate: Double?
)

sealed interface CurrencyManagerError {
    data class ExchangeRateUnavailable(val currencyCode: String) : CurrencyManagerError
}

private fun DisplayAmount.toRoundedMsats(info: CurrencyInfo, exchangeRate: Double?): Long? {
    val unroundedMsats = when (info.currency) {
        DisplayCurrency.Satoshi -> {
            if (minor > Long.MAX_VALUE / MSATS_PER_SAT) return null
            minor * MSATS_PER_SAT
        }

        is DisplayCurrency.Fiat -> {
            val rate = exchangeRate ?: return null
            val fiatMajor = minor.toDouble() / 10.0.pow(info.fractionDigits)
            (fiatMajor / rate * MSATS_PER_BITCOIN).roundToLong()
        }
    }
    if (unroundedMsats <= 0L || unroundedMsats > Long.MAX_VALUE - (MSATS_PER_SAT - 1L)) {
        return null
    }
    return ((unroundedMsats + MSATS_PER_SAT - 1L) / MSATS_PER_SAT) * MSATS_PER_SAT
}

private const val MSATS_PER_SAT = 1_000L
private const val MSATS_PER_BITCOIN = 100_000_000_000L
