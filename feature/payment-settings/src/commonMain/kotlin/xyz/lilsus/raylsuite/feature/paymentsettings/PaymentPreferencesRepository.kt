package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences

interface PaymentPreferencesRepository {
    val preferences: Flow<PaymentPreferences>

    suspend fun current(): PaymentPreferences

    suspend fun setConfirmationMode(mode: PaymentConfirmationMode)

    suspend fun setConfirmationThreshold(thresholdSats: Long)

    suspend fun setOfferToSaveNewTargets(enabled: Boolean)

    suspend fun setVibrateOnScan(enabled: Boolean)

    suspend fun setVibrateOnPayment(enabled: Boolean)

    suspend fun setShareUsage(enabled: Boolean)
}

class DefaultPaymentPreferencesRepository(private val settings: Settings) :
    PaymentPreferencesRepository {
    private val state = MutableStateFlow(loadPreferences())
    private val mutationMutex = Mutex()

    override val preferences: Flow<PaymentPreferences> = state.asStateFlow()

    override suspend fun current(): PaymentPreferences = state.value

    override suspend fun setConfirmationMode(mode: PaymentConfirmationMode) {
        update(
            transform = { it.copy(confirmationMode = mode) },
            persist = { preferences ->
                settings.putString(
                    KEY_CONFIRM_MODE,
                    when (preferences.confirmationMode) {
                        PaymentConfirmationMode.Always -> MODE_ALWAYS
                        PaymentConfirmationMode.Above -> MODE_ABOVE
                    }
                )
            }
        )
    }

    override suspend fun setConfirmationThreshold(thresholdSats: Long) {
        update(
            transform = { it.copy(thresholdSats = thresholdSats) },
            persist = { settings.putLong(KEY_CONFIRM_THRESHOLD_SATS, it.thresholdSats) }
        )
    }

    override suspend fun setOfferToSaveNewTargets(enabled: Boolean) {
        update(
            transform = { it.copy(offerToSaveNewTargets = enabled) },
            persist = {
                settings.putBoolean(KEY_OFFER_TO_SAVE_NEW_TARGETS, it.offerToSaveNewTargets)
            }
        )
    }

    override suspend fun setVibrateOnScan(enabled: Boolean) {
        update(
            transform = { it.copy(vibrateOnScan = enabled) },
            persist = { settings.putBoolean(KEY_VIBRATE_SCAN, it.vibrateOnScan) }
        )
    }

    override suspend fun setVibrateOnPayment(enabled: Boolean) {
        update(
            transform = { it.copy(vibrateOnPayment = enabled) },
            persist = { settings.putBoolean(KEY_VIBRATE_PAYMENT, it.vibrateOnPayment) }
        )
    }

    override suspend fun setShareUsage(enabled: Boolean) {
        update(
            transform = { it.copy(shareUsage = enabled) },
            persist = { settings.putBoolean(KEY_SHARE_USAGE, it.shareUsage) }
        )
    }

    private suspend fun update(
        transform: (PaymentPreferences) -> PaymentPreferences,
        persist: (PaymentPreferences) -> Unit
    ) {
        mutationMutex.withLock {
            val current = state.value
            val updated = transform(current).normalise()
            if (updated == current) return

            persist(updated)
            state.value = updated
        }
    }

    private fun loadPreferences(): PaymentPreferences {
        val confirmationMode =
            when (settings.getStringOrNull(KEY_CONFIRM_MODE)?.lowercase()) {
                MODE_ALWAYS -> PaymentConfirmationMode.Always
                else -> PaymentConfirmationMode.Above
            }
        val thresholdSats =
            settings.getLong(
                KEY_CONFIRM_THRESHOLD_SATS,
                PaymentPreferences.DEFAULT_CONFIRMATION_THRESHOLD_SATS
            )
        return PaymentPreferences(
            confirmationMode = confirmationMode,
            thresholdSats = thresholdSats,
            offerToSaveNewTargets = settings.getBoolean(KEY_OFFER_TO_SAVE_NEW_TARGETS, true),
            vibrateOnScan = settings.getBoolean(KEY_VIBRATE_SCAN, true),
            vibrateOnPayment = settings.getBoolean(KEY_VIBRATE_PAYMENT, true),
            shareUsage = settings.getBoolean(KEY_SHARE_USAGE, true)
        ).normalise()
    }

    private companion object {
        const val KEY_CONFIRM_MODE = "payments.confirmationMode"
        const val KEY_CONFIRM_THRESHOLD_SATS = "payments.confirmationThresholdSats"
        const val KEY_OFFER_TO_SAVE_NEW_TARGETS = "payments.offerToSaveNewTargets"
        const val KEY_VIBRATE_SCAN = "payments.vibrateOnScan"
        const val KEY_VIBRATE_PAYMENT = "payments.vibrateOnPayment"
        const val KEY_SHARE_USAGE = "payments.shareUsage"
        const val MODE_ALWAYS = "always"
        const val MODE_ABOVE = "above"
    }
}
