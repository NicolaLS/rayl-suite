package xyz.lilsus.rayl.providers.nwc.integration.nwc

import io.github.nicolals.nwc.NwcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.WalletStartMode

@OptIn(ExperimentalCoroutinesApi::class)
class NwcWalletConnectionTest {
    @Test
    fun foregroundWaitsForCancelledBackgroundDisposalAndCreatesANewClient() = runTest {
        val store = NwcCredentialStore(TestSecureStringStore())
        val key = "0".repeat(63) + "1"
        store.save(
            NwcCredentials(
                connectionUri = "nostr+walletconnect://$key?relay=wss%3A%2F%2Frelay.example&secret=$key",
                alias = null,
                metadata = discovery(setOf("pay_invoice", "lookup_invoice")).metadata
            )
        )
        val closeEntered = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        val disposed = mutableListOf<NwcClient>()
        val wallet = NwcWallet(
            credentialStore = store,
            scope = backgroundScope,
            httpClient = HttpClient(MockEngine) { engine { addHandler { error("No HTTP request expected") } } },
            isNetworkAvailable = { true },
            ownsHttpClient = true,
            closeClient = {
                it.close()
                disposed.add(it)
                if (disposed.size == 1) {
                    closeEntered.complete(Unit)
                    finishClose.await()
                }
            }
        )
        try {
            wallet.prewarm()
            val background = launch { wallet.onAppForegroundChanged(false) }
            closeEntered.await()
            background.cancel()
            val foreground = launch { wallet.onAppForegroundChanged(true) }
            runCurrent()
            assertFalse(foreground.isCompleted)
            finishClose.complete(Unit)
            background.join()
            foreground.join()
            wallet.close()
            assertEquals(2, disposed.size)
            assertNotSame(disposed[0], disposed[1])
        } finally {
            finishClose.complete(Unit)
            wallet.close()
        }
    }

    @Test
    fun freshSetupAndForegroundNeverRestoreOldCredentials() = runTest {
        val secure = TestSecureStringStore().apply { forbidReads = true }
        val wallet = NwcWallet(
            credentialStore = NwcCredentialStore(secure),
            scope = backgroundScope,
            httpClient = HttpClient(MockEngine) { engine { addHandler { error("No network request expected") } } },
            isNetworkAvailable = { true },
            ownsHttpClient = true,
            startMode = WalletStartMode.Fresh
        )
        assertNull(wallet.connection.value)
        wallet.onAppForegroundChanged(true)
        wallet.prewarm()
        assertEquals(0, secure.reads)
        wallet.close()
    }

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
