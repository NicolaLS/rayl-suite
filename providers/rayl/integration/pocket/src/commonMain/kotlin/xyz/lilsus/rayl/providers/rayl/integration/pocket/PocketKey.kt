package xyz.lilsus.rayl.providers.rayl.integration.pocket

import fr.acinq.bitcoin.MnemonicCode

/**
 * The pocket's BIP-39 phrase. Rayl generates it on the device and never asks the user to write it
 * down; it stays a standard phrase so a later emergency export works with any Spark wallet.
 *
 * TODO(production): Required before real users hold funds: a lost or reset phone loses this key.
 * Back it up with the platform's cloud sync, the owner's choice over Breez passkey login: a
 * synchronizable iCloud Keychain item on iOS and Google Block Store on Android, so a new phone
 * signed in to the same Apple or Google account restores the same pocket without prompting.
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
