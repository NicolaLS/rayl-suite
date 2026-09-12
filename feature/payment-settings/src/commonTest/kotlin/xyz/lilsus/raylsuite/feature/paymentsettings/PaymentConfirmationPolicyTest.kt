package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode

class PaymentConfirmationPolicyTest {
    @Test
    fun presetAmountAlwaysRequiresConfirmation() = runTest {
        val repository = DefaultPaymentPreferencesRepository(MapSettings())
        val policy = PaymentConfirmationPolicy(repository)

        assertTrue(
            policy.shouldConfirm(
                amountMsats = 1_000L,
                isManualEntry = false,
                isPresetTarget = true
            )
        )
    }

    @Test
    fun alwaysModeOnlyRequiresConfirmationForSuppliedAmounts() = runTest {
        val repository = DefaultPaymentPreferencesRepository(MapSettings())
        repository.setConfirmationMode(PaymentConfirmationMode.Always)
        val policy = PaymentConfirmationPolicy(repository)

        assertFalse(
            policy.shouldConfirm(
                amountMsats = 20_000_000L,
                isManualEntry = true,
                isPresetTarget = true
            )
        )
        assertTrue(
            policy.shouldConfirm(
                amountMsats = 1_000L,
                isManualEntry = false
            )
        )
    }

    @Test
    fun aboveModeSkipsManualEntryAndAppliesThresholdToScannedAmounts() = runTest {
        val repository = DefaultPaymentPreferencesRepository(MapSettings())
        val policy = PaymentConfirmationPolicy(repository)

        assertFalse(
            policy.shouldConfirm(
                amountMsats = 20_000_000L,
                isManualEntry = true
            )
        )
        assertFalse(
            policy.shouldConfirm(
                amountMsats = 9_999_000L,
                isManualEntry = false
            )
        )
        assertTrue(
            policy.shouldConfirm(
                amountMsats = 10_000_000L,
                isManualEntry = false
            )
        )
    }
}
