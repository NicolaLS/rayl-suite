package xyz.lilsus.rayl.providers.blink.integration.blink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlinkCredentialStoreTest {
    @Test
    fun storesAndClearsOneCredentialSet() {
        val store = BlinkCredentialStore(TestSecureStringStore())
        val credentials =
            BlinkCredentials(
                apiKey = "blink_test",
                selectedFundingWallet =
                    BlinkFundingWallet("wallet-id", BlinkWalletCurrency.USD)
            )

        store.save(credentials)
        assertEquals(credentials, store.read())

        store.clear()
        assertNull(store.read())
    }
}
