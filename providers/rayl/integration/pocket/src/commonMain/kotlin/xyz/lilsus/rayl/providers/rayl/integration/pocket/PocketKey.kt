package xyz.lilsus.rayl.providers.rayl.integration.pocket

import fr.acinq.bitcoin.MnemonicCode

/**
 * The pocket's BIP-39 phrase. Rayl generates it on the device and never asks the user to write it
 * down; it stays a standard phrase so a later emergency export works with any Spark wallet.
 *
 * TODO(production): Required before real users hold funds: a lost or reset phone loses this key.
 * Derive it from a passkey with Breez passkey login (guide/passkey.html): `connectWithPasskey`
 * once, then store the returned seed here so launches and payments never prompt. Use the shared
 * Breez relying party (register the app IDs with Breez) and a "Rayl" label, so the wallet differs
 * from other Breez apps'. On iOS, inject Breez's Swift `PasskeyProvider` into this Kotlin SDK's
 * `PasskeyClient`; Breez's Swift `PasskeyClient` would re-register the SDK callbacks. Keeping a
 * generated key where passkeys are unsupported is a stopgap that needs its own backup.
 */
class PocketKey internal constructor(internal val phrase: String) {
    override fun toString(): String = "PocketKey(<redacted>)"

    internal companion object {
        fun generate(): PocketKey =
            PocketKey(MnemonicCode.toMnemonics(secureRandomBytes(ENTROPY_BYTES)).joinToString(" "))

        private const val ENTROPY_BYTES = 16
    }
}

internal sealed interface PocketKeyLoad {
    data class Present(val key: PocketKey) : PocketKeyLoad

    /** No key was ever stored, so generating one cannot orphan funds. */
    data object Absent : PocketKeyLoad

    /** A key may exist but cannot be read now; never replace it. */
    data object Unavailable : PocketKeyLoad
}

internal interface PocketKeyStore {
    suspend fun load(): PocketKeyLoad

    /** Stores [key] only if no key exists; an existing key is never replaced. */
    suspend fun add(key: PocketKey): Boolean

    suspend fun delete(): Boolean
}

internal expect fun secureRandomBytes(size: Int): ByteArray
