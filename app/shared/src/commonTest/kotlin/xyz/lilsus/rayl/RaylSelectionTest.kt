package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.WalletStartMode

class RaylSelectionTest {
    @Test
    fun abandonedSetupIsNotRestoredAndOrphanCredentialsAreErasedBeforeChoosing() = runTest {
        RaylWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val selection = RaylSelection(settings)
            selection.prepare { true }
            selection.choose(wallet)
            assertEquals(WalletStartMode.Fresh, selection.startMode)
            assertNull(settings.getStringOrNull("rayl.wallet"))

            // The process can die after a provider writes credentials but before success is observed.
            val restarted = RaylSelection(settings)
            assertNotEquals(selection.sessionId, restarted.sessionId)
            val erased = mutableListOf<RaylWallet>()
            assertNull(restarted.wallet.value)
            assertFailsWith<IllegalStateException> { restarted.choose(wallet) }
            restarted.prepare {
                erased.add(it)
                true
            }
            assertEquals(RaylWallet.entries.toList(), erased)
            restarted.choose(wallet)
            assertEquals(WalletStartMode.Fresh, restarted.startMode)
        }
    }

    @Test
    fun onlyTheConfiguredWalletIsRestoredAndOtherCredentialsAreErased() = runTest {
        RaylWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val selection = RaylSelection(settings)
            selection.prepare { true }
            selection.choose(wallet)
            selection.connected(wallet)
            val restarted = RaylSelection(settings)
            val erased = mutableListOf<RaylWallet>()
            restarted.prepare {
                erased.add(it)
                true
            }
            assertEquals(wallet, restarted.wallet.value)
            assertEquals(WalletStartMode.Restore, restarted.startMode)
            assertEquals(RaylWallet.entries.filter { it != wallet }, erased)
            assertFailsWith<IllegalStateException> { restarted.choose(wallet) }
        }
    }

    @Test
    fun removalAndSwitchingRequireCleanupAndAlwaysStartFresh() = runTest {
        RaylWallet.entries.forEach { current ->
            RaylWallet.entries.forEach { next ->
                val settings = MapSettings()
                val selection = RaylSelection(settings)
                selection.prepare { true }
                selection.choose(current)
                selection.connected(current)
                val previousSession = selection.sessionId
                selection.clear()
                assertNull(RaylSelection(settings).wallet.value)
                assertFailsWith<IllegalStateException> { selection.choose(next) }
                val erased = mutableListOf<RaylWallet>()
                selection.prepare {
                    erased.add(it)
                    true
                }
                assertEquals(RaylWallet.entries.toList(), erased)
                selection.choose(next)
                assertNotEquals(previousSession, selection.sessionId)
                assertEquals(WalletStartMode.Fresh, selection.startMode)
                assertNull(settings.getStringOrNull("rayl.wallet"))
                assertTrue(selection.welcomeCompleted.value)
            }
        }
    }

    @Test
    fun failedCleanupBlocksTheChooserAndCanBeRetriedAfterRestart() = runTest {
        val settings = MapSettings()
        val selection = RaylSelection(settings)
        selection.prepare { it != RaylWallet.Nwc }
        assertEquals(WalletPreparation.Failed, selection.preparation.value)
        assertFailsWith<IllegalStateException> { selection.choose(RaylWallet.Blink) }
        selection.prepare { throw IllegalStateException("secure storage unavailable") }
        assertEquals(WalletPreparation.Failed, selection.preparation.value)
        val restarted = RaylSelection(settings)
        restarted.prepare { true }
        assertEquals(WalletPreparation.Ready, restarted.preparation.value)
        restarted.choose(RaylWallet.Nwc)
    }

    @Test
    fun cancelledCleanupDoesNotReportSuccess() = runTest {
        val selection = RaylSelection(MapSettings())
        assertFailsWith<CancellationException> {
            selection.prepare { throw CancellationException() }
        }
        assertEquals(WalletPreparation.Cleaning, selection.preparation.value)
        selection.prepare { true }
        assertEquals(WalletPreparation.Ready, selection.preparation.value)
    }

    @Test
    fun lateConnectionEventsCannotResurrectARemovedWallet() = runTest {
        val settings = MapSettings()
        val selection = RaylSelection(settings)
        selection.prepare { true }
        selection.choose(RaylWallet.Nwc)
        selection.clear()
        selection.connected(RaylWallet.Nwc)
        assertNull(settings.getStringOrNull("rayl.wallet"))
    }

    @Test
    fun unknownStoredWalletIsNotRestored() = runTest {
        val settings = MapSettings()
        settings.putString("rayl.wallet", "unknown-wallet")
        val selection = RaylSelection(settings)
        val erased = mutableListOf<RaylWallet>()
        selection.prepare {
            erased.add(it)
            true
        }
        assertNull(selection.wallet.value)
        assertEquals(RaylWallet.entries.toList(), erased)
    }
}
