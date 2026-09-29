package xyz.lilsus.raylsuite.backend.rayl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.widgetspec.WidgetUrl

/** The reviewed catalog for one environment, as kept in `src/main/resources/catalog/`. */
@Serializable
data class CatalogSource(
    val entries: List<CatalogSourceEntry>,
    val blocked: List<String> = emptyList()
) {
    /** Every problem, so a bad edit fails at startup instead of reaching users. */
    fun problems(): List<String> = buildList {
        duplicates(entries.map { it.id }).forEach { add("duplicate id $it") }
        duplicates(entries.map { it.url }).forEach { add("duplicate url $it") }
        entries.forEach { entry ->
            if (!ID.matches(entry.id)) add("invalid id ${entry.id}")
            if (entry.section !in SECTIONS) add("unknown section ${entry.section} for ${entry.id}")
            val normalized = WidgetUrl.parse(entry.url)?.value
            if (normalized != entry.url) add("url must be normalized HTTPS: ${entry.url}")
        }
        blocked.filterNot(::isBlockRule).forEach { add("invalid blocked rule $it") }
    }

    /** Adds the emergency rules from `RAYL_BLOCKED` (comma-separated hosts or URLs). */
    fun withBlocked(extra: String?): CatalogSource {
        val rules = extra.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        return copy(blocked = (blocked + rules).distinct())
    }

    companion object {
        val SECTIONS = setOf("mobile", "bills", "gift_cards", "info", "other")
        private val ID = Regex("""^[a-z0-9][a-z0-9-]{0,62}$""")
        private val HOST =
            Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")

        private fun duplicates(values: List<String>) =
            values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        private fun isBlockRule(rule: String) = if (rule.startsWith("https://")) {
            WidgetUrl.parse(rule)?.value == rule
        } else {
            HOST.matches(rule)
        }

        fun load(environment: String): CatalogSource {
            val resource = CatalogSource::class.java.getResource("/catalog/$environment.json")
                ?: error("No catalog for environment '$environment'")
            return Json.decodeFromString(serializer(), resource.readText())
        }
    }
}

@Serializable
data class CatalogSourceEntry(
    val id: String,
    val section: String,
    val url: String,
    val discoverable: Boolean = true
)
