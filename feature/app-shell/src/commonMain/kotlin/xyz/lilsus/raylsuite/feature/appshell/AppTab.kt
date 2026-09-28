package xyz.lilsus.raylsuite.feature.appshell

/**
 * The top-level destinations of an app. The order is the tab-bar order and the stored value is
 * the stable identity platform shells use to name a tab.
 */
enum class AppTab(val storedValue: String) {
    Scan("scan"),
    Hub("hub"),
    Settings("settings");

    companion object {
        val Default: AppTab = Scan

        fun fromStoredValue(value: String?): AppTab =
            entries.firstOrNull { it.storedValue == value } ?: Default
    }
}
