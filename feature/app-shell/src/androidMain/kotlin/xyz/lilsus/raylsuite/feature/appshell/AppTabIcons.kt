package xyz.lilsus.raylsuite.feature.appshell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Material icons for the Android tab bar. iOS names its own SF Symbols in the native shell. */
internal val AppTab.icon: ImageVector
    get() =
        when (this) {
            AppTab.Scan -> Icons.Filled.QrCodeScanner
            AppTab.Hub -> Icons.Filled.GridView
            AppTab.Settings -> Icons.Filled.Settings
        }
