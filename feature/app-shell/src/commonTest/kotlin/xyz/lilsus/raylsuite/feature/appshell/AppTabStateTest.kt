package xyz.lilsus.raylsuite.feature.appshell

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTabStateTest {
    @Test
    fun openingAReceiptFromAnotherTabReturnsToScan() {
        val state = AppTabState()
        state.select(AppTab.Hub)
        state.openTransaction("receipt")
        assertEquals(AppTab.Scan, state.selectedTab.value)
        assertEquals("receipt", state.selectedTransactionId.value)
        state.selectTransaction(null)
        assertEquals(AppTab.Scan, state.selectedTab.value)
    }

    @Test
    fun obsoleteRecentTabSelectionFallsBackToScan() {
        assertEquals(AppTab.Scan, AppTab.fromStoredValue("recent"))
    }
}
