package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaylSelectionTest {
    @Test
    fun anotherConnectionCannotReplaceTheSelectedExperience() {
        RaylWallet.entries.forEach { current ->
            RaylWallet.entries.forEach { next ->
                val settings = MapSettings()
                val selection = RaylSelection(settings)
                selection.choose(current)
                assertFailsWith<IllegalStateException> { selection.choose(next) }
                assertEquals(current, RaylSelection(settings).wallet.value)

                selection.clear()
                assertNull(RaylSelection(settings).wallet.value)
                selection.choose(next)
                assertEquals(next, RaylSelection(settings).wallet.value)
                assertTrue(selection.welcomeCompleted.value)
            }
        }
    }

    @Test
    fun eachWalletCanBeSelectedAndRestored() {
        RaylWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val selection = RaylSelection(settings)

            selection.choose(wallet)

            assertEquals(wallet, selection.wallet.value)
            assertEquals(wallet.name, settings.getStringOrNull("rayl.wallet"))
            assertEquals(wallet, RaylSelection(settings).wallet.value)
        }
    }

    @Test
    fun unknownStoredWalletIsNotRestored() {
        val settings = MapSettings()
        settings.putString("rayl.wallet", "unknown-wallet")

        assertNull(RaylSelection(settings).wallet.value)
    }
}
