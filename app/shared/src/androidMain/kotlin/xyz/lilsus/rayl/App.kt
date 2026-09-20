package xyz.lilsus.rayl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import xyz.lilsus.flint.FlintEnvironment
import xyz.lilsus.flint.SparkDeepLinks
import xyz.lilsus.flint.SparkExperience
import xyz.lilsus.lasr.LasrDeepLinks
import xyz.lilsus.lasr.NwcExperience
import xyz.lilsus.rayl.providers.blink.BlinkDeepLinks
import xyz.lilsus.rayl.providers.blink.BlinkExperience
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen
import xyz.lilsus.raylsuite.feature.settings.PerformanceDiagnostics
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

@Composable
fun App(
    sparkEnvironment: FlintEnvironment,
    breezApiKey: String? = null,
    performanceDiagnostics: PerformanceDiagnostics? = null
) {
    val settings = rememberAppSettings(RAYL_PREFERENCES)
    val selection = remember { RaylSelection(settings) }
    val wallet by selection.wallet.collectAsStateWithLifecycle()
    val welcomeCompleted by selection.welcomeCompleted.collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf<Boolean?>(null) }
    val theme = remember(wallet) { DefaultThemePreferences(settings) }
    val preference by theme.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    var message by remember { mutableStateOf<Int?>(null) }
    val leave = {
        RaylDeepLinks.clear()
        BlinkDeepLinks.clear()
        LasrDeepLinks.clear()
        SparkDeepLinks.clear()
        message = null
        connected = null
        selection.clear()
    }
    LaunchedEffect(selection, wallet) {
        RaylDeepLinks.events.collect { uri ->
            val selected = selection.wallet.value
            if (uri.substringBefore(':').equals("nostr+walletconnect", true)) {
                if (selected == null) {
                    selection.choose(RaylWallet.Nwc)
                    LasrDeepLinks.emit(uri)
                } else if (selected == RaylWallet.Nwc &&
                    !snapshotFlow { connected }.filterNotNull().first()
                ) {
                    LasrDeepLinks.emit(uri)
                } else {
                    message = R.string.connection_exists
                }
            } else if (selected == null) {
                message = R.string.connect_to_pay
            } else if (!snapshotFlow { connected }.filterNotNull().first()) {
                message = R.string.connect_to_pay
            } else {
                when (selected) {
                    RaylWallet.Blink -> BlinkDeepLinks.emit(uri)
                    RaylWallet.Nwc -> LasrDeepLinks.emit(uri)
                    RaylWallet.Spark -> SparkDeepLinks.emit(uri)
                }
            }
        }
    }
    RaylSuiteTheme(themePreference = preference) {
        key(wallet) {
            when (wallet) {
                RaylWallet.Blink -> BlinkExperience(
                    configuration = RAYL_BLINK,
                    performanceDiagnostics = performanceDiagnostics,
                    onRemoved = leave,
                    onChooseWallet = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Blink) connected = it
                    }
                )

                RaylWallet.Nwc -> NwcExperience(
                    configuration = RAYL_NWC,
                    performanceDiagnostics = performanceDiagnostics,
                    onRemoved = leave,
                    onChooseWallet = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Nwc) connected = it
                    }
                )

                RaylWallet.Spark -> SparkExperience(
                    configuration = RAYL_SPARK,
                    environment = sparkEnvironment,
                    breezApiKey = breezApiKey,
                    performanceDiagnostics = performanceDiagnostics,
                    onRemoved = leave,
                    onChooseWallet = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Spark) connected = it
                    }
                )

                null -> Surface(modifier = Modifier.fillMaxSize()) {
                    if (!welcomeCompleted) {
                        WelcomeScreen(
                            title = stringResource(R.string.welcome_title),
                            subtitle = "",
                            description = stringResource(R.string.welcome_body),
                            actionTitle = stringResource(R.string.get_started),
                            onGetStarted = selection::completeWelcome
                        )
                    } else {
                        Column(
                            modifier = Modifier.safeDrawingPadding().verticalScroll(
                                rememberScrollState()
                            ).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text(
                                stringResource(R.string.choose_wallet),
                                style = MaterialTheme.typography.headlineLarge
                            )
                            Text(stringResource(R.string.choose_body))
                            RaylWallet.entries.forEach { provider ->
                                val (title, body) = when (provider) {
                                    RaylWallet.Blink ->
                                        R.string.blink_title to
                                            R.string.blink_body

                                    RaylWallet.Nwc -> R.string.nwc_title to R.string.nwc_body

                                    RaylWallet.Spark -> R.string.spark_title to R.string.spark_body
                                }
                                WalletChoice(title, body) { selection.choose(provider) }
                            }
                        }
                    }
                }
            }
        }
        message?.let { resource ->
            AlertDialog(onDismissRequest = {
                message = null
            }, text = { Text(stringResource(resource)) }, confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.close)) }
            })
        }
    }
}

@Composable
private fun WalletChoice(title: Int, description: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
