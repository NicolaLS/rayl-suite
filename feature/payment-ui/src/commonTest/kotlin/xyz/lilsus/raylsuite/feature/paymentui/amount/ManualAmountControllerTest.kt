package xyz.lilsus.raylsuite.feature.paymentui.amount

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

class ManualAmountControllerTest {
    @Test
    fun satoshiPreferenceHasNoCurrencySwitch() {
        val controller = ManualAmountController(ManualAmountConfig(CurrencyCatalog.infoFor("SAT"), null))
        controller.handleKeyPress(ManualAmountKey.Digit(5))
        assertNull(controller.current().alternativeCurrency)
        val unchanged = controller.current()
        assertEquals(unchanged, controller.handleKeyPress(ManualAmountKey.SwitchCurrency))
    }

    @Test
    fun fiatStartsPrimaryAndSwitchesTheEnteredValueToWholeSats() {
        val controller = ManualAmountController(usdConfig())
        assertEquals(DisplayCurrency.Fiat("USD"), controller.current().currency)
        controller.presetAmount(DisplayAmount(100, DisplayCurrency.Fiat("USD")))
        assertEquals(DisplayAmount(1667, DisplayCurrency.Satoshi), controller.current().equivalentAmount)

        val sats = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1667, DisplayCurrency.Satoshi), controller.enteredAmount())
        assertFalse(sats.allowDecimal)
        assertEquals(DisplayCurrency.Fiat("USD"), sats.alternativeCurrency)

        val fiat = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(100, DisplayCurrency.Fiat("USD")), fiat.amount)
        assertTrue(fiat.allowDecimal)
    }

    @Test
    fun roundTripPreservesExactSatsUntilTheUserEditsFiat() {
        val controller = ManualAmountController(usdConfig())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.handleKeyPress(ManualAmountKey.Digit(1))
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1, DisplayCurrency.Fiat("USD")), controller.enteredAmount())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1, DisplayCurrency.Satoshi), controller.enteredAmount())

        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.handleKeyPress(ManualAmountKey.Backspace)
        controller.handleKeyPress(ManualAmountKey.Digit(2))
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(34, DisplayCurrency.Satoshi), controller.enteredAmount())
    }

    @Test
    fun rateRefreshKeepsSatEntryAndNewEntryRestoresFiat() {
        val controller = ManualAmountController(usdConfig())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.presetAmount(DisplayAmount(1000, DisplayCurrency.Satoshi))
        val refreshed = controller.reset(usdConfig().copy(exchangeRate = 100_000.0), clearInput = false)
        assertEquals(DisplayAmount(1000, DisplayCurrency.Satoshi), refreshed.amount)
        assertEquals(DisplayAmount(100, DisplayCurrency.Fiat("USD")), refreshed.equivalentAmount)
        val reset = controller.reset(usdConfig())
        assertEquals(DisplayCurrency.Fiat("USD"), reset.currency)
        assertNull(reset.amount)
    }

    @Test
    fun satoshiEntryWorksWithoutAnExchangeRate() {
        val controller = ManualAmountController(usdConfig().copy(exchangeRate = null))
        controller.handleKeyPress(ManualAmountKey.Digit(5))
        assertNull(controller.current().equivalentAmount)
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertNull(controller.enteredAmount())
        controller.handleKeyPress(ManualAmountKey.Digit(2))
        assertEquals(DisplayAmount(2, DisplayCurrency.Satoshi), controller.enteredAmount())
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
        assertNull(controller.current().equivalentAmount)
    }

    @Test
    fun satoshiSwitchUsesSatoshiBoundsAndRangePresets() {
        val controller = ManualAmountController(usdConfig().copy(minMsats = 1501, maxMsats = 9500))
        val sats = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(2, DisplayCurrency.Satoshi), sats.min)
        assertEquals(DisplayAmount(9, DisplayCurrency.Satoshi), sats.max)
        controller.handleKeyPress(ManualAmountKey.Digit(1))
        assertEquals(RangeStatus.BelowMin(sats.min!!), controller.current().rangeStatus)
        controller.presetAmount(sats.max!!)
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
        assertEquals(DisplayAmount(9, DisplayCurrency.Satoshi), controller.enteredAmount())
    }

    @Test
    fun zeroDecimalFiatStaysIntegralInBothUnits() {
        val controller = ManualAmountController(ManualAmountConfig(CurrencyCatalog.infoFor("JPY"), 10_000_000.0))
        controller.handleKeyPress(ManualAmountKey.Digit(5))
        assertFalse(controller.current().allowDecimal)
        val sats = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(50, DisplayCurrency.Satoshi), sats.amount)
        val yen = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(5, DisplayCurrency.Fiat("JPY")), yen.amount)
        assertFalse(yen.allowDecimal)
    }

    private fun usdConfig() = ManualAmountConfig(CurrencyCatalog.infoFor("USD"), 60_000.0)

    @Test
    fun reportsBelowMinWhenUnderRange() {
        val usd = CurrencyCatalog.infoFor("USD")
        val controller =
            ManualAmountController(
                ManualAmountConfig(
                    info = usd,
                    exchangeRate = 60_000.0,
                    min = DisplayAmount(100, usd.currency),
                    max = DisplayAmount(500, usd.currency),
                    minMsats = 1_500_000L,
                    maxMsats = 9_000_000L
                )
            )

        controller.handleKeyPress(ManualAmountKey.Decimal)
        val state = controller.handleKeyPress(ManualAmountKey.Digit(5))

        assertEquals(
            RangeStatus.BelowMin(DisplayAmount(100, usd.currency)),
            state.rangeStatus
        )
    }
}
