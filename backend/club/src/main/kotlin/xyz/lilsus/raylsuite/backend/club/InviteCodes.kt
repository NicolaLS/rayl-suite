package xyz.lilsus.raylsuite.backend.club

import java.security.SecureRandom

/**
 * Invite codes are 12 random characters, shown as `XXXX-XXXX-XXXX`. The alphabet leaves out
 * characters that are easy to misread (0/O, 1/I/L, U), giving 30^12 ≈ 5·10^17 codes, far beyond
 * guessing.
 */
object InviteCodes {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    private const val LENGTH = 12
    private val random = SecureRandom()

    /** A new normalized code. */
    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** The normalized form of a typed code, or null if it cannot be an invite code. */
    fun normalize(typed: String): String? {
        val code = typed.uppercase().filterNot { it == '-' || it.isWhitespace() }
        return code.takeIf { it.length == LENGTH && it.all(ALPHABET::contains) }
    }

    fun format(code: String): String = code.chunked(4).joinToString("-")
}
