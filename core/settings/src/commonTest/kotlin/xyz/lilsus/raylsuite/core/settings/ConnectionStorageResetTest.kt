package xyz.lilsus.raylsuite.core.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStorageResetTest {
    @Test
    fun interruptedErasureResumesWithoutClearingPreferencesOrAnotherProvider() {
        val app = MapSettings().apply {
            putString("appearance.selectedTheme", "dark")
            putString("contacts.document", "saved contacts")
            putString("payments.confirmationMode", "above")
            putLong("payments.confirmationThresholdSats", 5000L)
            putBoolean("onboarding.completed", true)
            putString("hub", "saved")
        }
        val blink = MapSettings().apply { putString("connection.preference", "old setting") }
        val nwc = MapSettings().apply { putString("connection.preference", "other setting") }
        val secrets = FakeSecrets()
        val reset = ConnectionStorageReset(app, blink, secrets, "blink.removing")
        reset.begin()
        secrets.fail = true
        assertFailsWith<IllegalStateException> { reset.finish() }
        assertTrue(reset.pending)
        secrets.fail = false
        assertTrue(ConnectionStorageReset(app, blink, secrets, "blink.removing").resume())
        assertNull(blink.getStringOrNull("connection.preference"))
        assertEquals("other setting", nwc.getStringOrNull("connection.preference"))
        assertEquals("dark", app.getStringOrNull("appearance.selectedTheme"))
        assertEquals("saved contacts", app.getStringOrNull("contacts.document"))
        assertEquals("above", app.getStringOrNull("payments.confirmationMode"))
        assertEquals(5000L, app.getLongOrNull("payments.confirmationThresholdSats"))
        assertTrue(app.getBoolean("onboarding.completed", false))
        assertEquals("saved", app.getStringOrNull("hub"))
        assertFalse(reset.pending)
        assertFalse(reset.resume())
        assertTrue(secrets.cleared)
    }
}

private class FakeSecrets : SecureStringStore {
    var fail = false
    var cleared = false
    override fun putString(key: String, value: String) = Unit
    override fun getStringOrNull(key: String): String? = null
    override fun remove(key: String) = Unit
    override fun clear() {
        check(!fail)
        cleared = true
    }
}
