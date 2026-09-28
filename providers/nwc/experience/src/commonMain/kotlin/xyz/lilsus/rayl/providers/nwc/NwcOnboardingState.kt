package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NwcOnboardingState(private val settings: Settings) {
    private val mutableCompleted =
        MutableStateFlow(settings.getBoolean(KEY_COMPLETED, false))

    val completed: StateFlow<Boolean> = mutableCompleted.asStateFlow()

    private val mutableEducationStep =
        MutableStateFlow(settings.getString(KEY_EDUCATION_STEP, "features"))
    val educationStep = mutableEducationStep.asStateFlow()

    fun moveToEducationStep(step: String) {
        require(step in setOf("features", "autoPay", "agreement"))
        settings.putString(KEY_EDUCATION_STEP, step)
        mutableEducationStep.value = step
    }

    fun complete() {
        if (mutableCompleted.value) return

        settings.putBoolean(KEY_COMPLETED, true)
        mutableCompleted.value = true
    }

    fun nwcDeepLinkTarget(): NwcNwcDeepLinkTarget = if (completed.value) {
        NwcNwcDeepLinkTarget.Settings
    } else {
        NwcNwcDeepLinkTarget.Onboarding
    }

    fun canHandlePaymentDeepLink(walletConnected: Boolean): Boolean =
        completed.value && walletConnected

    private companion object {
        const val KEY_EDUCATION_STEP = "nwc.onboarding.education.step"
        const val KEY_COMPLETED = "onboarding.completed"
    }
}

internal enum class NwcNwcDeepLinkTarget {
    Onboarding,
    Settings
}
