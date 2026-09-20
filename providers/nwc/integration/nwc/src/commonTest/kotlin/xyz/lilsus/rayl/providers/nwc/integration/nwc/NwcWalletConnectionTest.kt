package xyz.lilsus.rayl.providers.nwc.integration.nwc

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class NwcWalletConnectionTest {
    @Test
    fun connectionMissingEitherRequiredMethodCannotBeSaved() = runTest {
        val store = NwcCredentialStore(TestSecureStringStore())
        val wallet =
            NwcWallet(
                credentialStore = store,
                scope = this,
                httpClient =
                    HttpClient(MockEngine) {
                        engine {
                            addHandler { error("No HTTP request expected") }
                        }
                    },
                isNetworkAvailable = { true },
                ownsHttpClient = true
            )

        listOf(setOf("pay_invoice"), setOf("lookup_invoice")).forEach { methods ->
            val exception =
                assertFailsWith<NwcConnectionException> {
                    wallet.connect(discovery(methods), alias = null)
                }
            assertEquals(NwcConnectionError.RequiredMethodsMissing, exception.error)
            assertNull(wallet.connection.value)
            assertNull(store.read())
        }

        wallet.close()
    }

    private fun discovery(methods: Set<String>) = NwcWalletDiscovery(
        connectionUri = "nostr+walletconnect://not-used",
        walletPublicKey = "wallet",
        relayUrls = listOf("wss://relay.example"),
        lightningAddress = null,
        aliasSuggestion = null,
        metadata =
            NwcWalletMetadata(
                methods = methods,
                encryptionSchemes = setOf("nip44_v2"),
                negotiatedEncryption = "nip44_v2",
                encryptionDefaultedToNip04 = false,
                notifications = emptySet(),
                network = null,
                color = null
            )
    )
}
