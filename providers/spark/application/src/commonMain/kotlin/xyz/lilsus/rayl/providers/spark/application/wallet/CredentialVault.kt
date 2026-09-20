package xyz.lilsus.rayl.providers.spark.application.wallet

class WalletCredential private constructor(val value: String) {
    override fun toString(): String = "WalletCredential(<redacted>)"

    companion object {
        fun fromMnemonic(input: String): WalletCredential {
            val normalized = input.trim().lowercase().split(
                Regex("\\s+")
            ).filter(String::isNotBlank).joinToString(" ")
            require(normalized.split(' ').size in setOf(12, 15, 18, 21, 24))
            return WalletCredential(normalized)
        }
    }
}

interface CredentialVault {
    suspend fun load(): CredentialLoadResult
    suspend fun store(credential: WalletCredential): CredentialStoreResult
    suspend fun delete(): CredentialDeleteResult
}

sealed interface CredentialLoadResult {
    data class Available(val credential: WalletCredential) : CredentialLoadResult {
        override fun toString(): String = "CredentialLoadResult.Available(<redacted>)"
    }

    data object Absent : CredentialLoadResult
    data object Unavailable : CredentialLoadResult
    data object Invalidated : CredentialLoadResult
    data object Corrupt : CredentialLoadResult
}

enum class CredentialStoreResult {
    STORED,
    ALREADY_EXISTS,
    UNAVAILABLE,
    INVALIDATED,
    FAILED
}

enum class CredentialDeleteResult {
    ABSENT,
    DELETED,
    FAILED
}
