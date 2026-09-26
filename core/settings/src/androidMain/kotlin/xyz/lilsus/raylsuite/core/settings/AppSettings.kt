package xyz.lilsus.raylsuite.core.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

@Composable
fun rememberAppSettings(storageName: String): Settings {
    val context = LocalContext.current.applicationContext
    return remember(context, storageName) {
        createAppSettings(context, storageName)
    }
}

fun createAppSettings(context: Context, storageName: String): Settings = SharedPreferencesSettings(
    context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
)
