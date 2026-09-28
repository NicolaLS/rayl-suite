package xyz.lilsus.raylsuite.feature.appshell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped tab selection, held outside composition so a native shell can drive it. Android
 * collects it into a navigation bar; iOS mirrors it into a `TabView` selection.
 */
class AppTabState {
    private val mutableSelectedTab = MutableStateFlow(AppTab.Default)
    val selectedTab: StateFlow<AppTab> = mutableSelectedTab.asStateFlow()

    private val mutableSelectedTransactionId = MutableStateFlow<String?>(null)
    val selectedTransactionId: StateFlow<String?> = mutableSelectedTransactionId.asStateFlow()

    fun select(tab: AppTab) {
        mutableSelectedTab.value = tab
    }

    fun selectTransaction(id: String?) {
        mutableSelectedTransactionId.value = id
    }

    /** Opens one transaction's detail inside Scan. */
    fun openTransaction(id: String) {
        mutableSelectedTransactionId.value = id
        mutableSelectedTab.value = AppTab.Scan
    }

    /** A payment always presents on the Scan tab, wherever it was started from. */
    fun requestScan() {
        mutableSelectedTab.value = AppTab.Scan
    }
}
