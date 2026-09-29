package xyz.lilsus.raylsuite.core.widgetspec

/**
 * A strictly parsed HTTPS URL. Anything a user could be misled by, such as user info
 * (`https://claro.com.sv@evil.example`), non-ASCII hosts, or fragments, is rejected rather than
 * normalized.
 */
class WidgetUrl private constructor(
    val value: String,
    /** Lowercase ASCII host; punycode labels stay as they are so look-alikes remain visible. */
    val host: String,
    val port: Int?
) {
    /** Scheme, host and port. Every URL a definition makes the client call must share it. */
    val origin: String get() = if (port == null) "https://$host" else "https://$host:$port"

    fun sameOriginAs(other: WidgetUrl): Boolean = origin == other.origin

    override fun equals(other: Any?): Boolean = other is WidgetUrl && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 2048
        private const val MAX_HOST_LENGTH = 253
        private val pattern =
            Regex("""^https://([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(/[^\s#]*)?$""")
        private val label = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")

        fun parse(raw: String): WidgetUrl? {
            if (raw.length > MAX_LENGTH || raw.any { it.code !in 0x21..0x7E }) return null
            val match = pattern.matchEntire(raw) ?: return null
            val host = match.groupValues[1].lowercase()
            if (host.length > MAX_HOST_LENGTH) return null
            val labels = host.split('.')
            // A dotless or numeric-only host is an intranet name or an IP literal, never a provider.
            if (labels.size < 2 || labels.any { !label.matches(it) }) return null
            if (labels.last().all(Char::isDigit)) return null
            val port = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt()
            if (port != null && port !in 1..65_535) return null
            val normalizedPort = port?.takeIf { it != 443 }
            val path = match.groupValues[3].ifEmpty { "/" }
            val authority = if (normalizedPort == null) host else "$host:$normalizedPort"
            return WidgetUrl("https://$authority$path", host, normalizedPort)
        }
    }
}
