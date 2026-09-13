package xyz.lilsus.raylsuite.feature.appshell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.feature.appshell.R

/**
 * The Android tab shell: a Material 3 navigation bar over the selected tab's content. iOS uses a
 * native `TabView` instead and renders one tab's content per view controller.
 */
@Composable
fun AppTabScaffold(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<AppTab> = AppTab.entries,
    recentBadgeCount: Int = 0,
    content: @Composable (AppTab) -> Unit
) {
    val tabContentState = rememberSaveableStateHolder()
    Scaffold(
        modifier = modifier.testTag(AppShellTestTags.SCAFFOLD),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val label = stringResource(tab.labelResource())
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.testTag(AppShellTestTags.tab(tab)),
                        icon = {
                            if (tab == AppTab.Recent && recentBadgeCount > 0) {
                                BadgedBox(
                                    badge = { Badge { Text(recentBadgeCount.badgeLabel()) } }
                                ) {
                                    Icon(imageVector = tab.icon, contentDescription = null)
                                }
                            } else {
                                Icon(imageVector = tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
        ) {
            tabContentState.SaveableStateProvider(selectedTab.storedValue) {
                content(selectedTab)
            }
        }
    }
}

private fun Int.badgeLabel(): String = if (this > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$this"

@StringRes
private fun AppTab.labelResource(): Int = when (this) {
    AppTab.Scan -> R.string.app_tab_scan
    AppTab.Recent -> R.string.app_tab_recent
    AppTab.Hub -> R.string.app_tab_hub
    AppTab.Settings -> R.string.app_tab_settings
}

private const val MAX_BADGE_COUNT = 9

object AppShellTestTags {
    const val SCAFFOLD = "app_tab_scaffold"

    fun tab(tab: AppTab): String = "app_tab_${tab.storedValue}"
}
