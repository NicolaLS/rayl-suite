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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkDeepLinks
import xyz.lilsus.rayl.providers.blink.BlinkExperience
import xyz.lilsus.rayl.providers.nwc.NwcDeepLinks
import xyz.lilsus.rayl.providers.nwc.NwcExperience
import xyz.lilsus.rayl.providers.spark.SparkDeepLinks
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkExperience
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

@Composable
fun App(sparkEnvironment: SparkEnvironment, breezApiKey: String? = null) {
    val settings = rememberAppSettings(RAYL_PREFERENCES)
    val selection = rememberRetainedInstance(
        key = "rayl-selection",
        factory = { RaylSelection(settings) },
        onDispose = {}
    )
    val wallet by selection.wallet.collectAsStateWithLifecycle()
    val preparation by selection.preparation.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    LaunchedEffect(selection, wallet) {
        selection.prepare { clearSavedWallet(context, it) }
    }
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
        NwcDeepLinks.clear()
        SparkDeepLinks.clear()
        message = null
        connected = null
        selection.clear()
    }
    LaunchedEffect(selection, wallet) {
        RaylDeepLinks.events.collect { uri ->
            selection.preparation.first { it == WalletPreparation.Ready }
            val selected = selection.wallet.value
            if (uri.substringBefore(':').equals("nostr+walletconnect", true)) {
                if (selected == null) {
                    selection.choose(RaylWallet.Nwc)
                    NwcDeepLinks.emit(uri)
                } else if (selected == RaylWallet.Nwc &&
                    !snapshotFlow { connected }.filterNotNull().first()
                ) {
                    NwcDeepLinks.emit(uri)
                } else {
                    message = R.string.connection_exists
                }
            } else if (selected == null) {
                message = R.string.connect_to_pay
            } else if (!snapshotFlow { connected }.filterNotNull().first()) {
                // FIXME: Spark cold-start reports Loading -> false before async restore finishes,
                // which can reject a cold-start payment deep link as disconnected.
                message = R.string.connect_to_pay
            } else {
                when (selected) {
                    RaylWallet.Blink -> BlinkDeepLinks.emit(uri)
                    RaylWallet.Nwc -> NwcDeepLinks.emit(uri)
                    RaylWallet.Spark -> SparkDeepLinks.emit(uri)
                }
            }
        }
    }
    RaylSuiteTheme(themePreference = preference) {
        if (preparation != WalletPreparation.Ready) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (preparation == WalletPreparation.Failed) {
                    Text(stringResource(R.string.wallet_cleanup_failed))
                    TextButton(onClick = {
                        scope.launch { selection.prepare { clearSavedWallet(context, it) } }
                    }) {
                        Text(stringResource(R.string.wallet_cleanup_retry))
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@RaylSuiteTheme
        }
        key(wallet, selection.sessionId) {
            when (wallet) {
                RaylWallet.Blink -> BlinkExperience(
                    configuration = RAYL_BLINK,
                    startMode = selection.startMode,
                    onRemoved = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Blink) {
                            connected = it
                            if (it) selection.connected(RaylWallet.Blink)
                        }
                    }
                )

                RaylWallet.Nwc -> NwcExperience(
                    configuration = RAYL_NWC,
                    startMode = selection.startMode,
                    onRemoved = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Nwc) {
                            connected = it
                            if (it) selection.connected(RaylWallet.Nwc)
                        }
                    }
                )

                RaylWallet.Spark -> SparkExperience(
                    configuration = RAYL_SPARK,
                    startMode = selection.startMode,
                    environment = sparkEnvironment,
                    breezApiKey = breezApiKey,
                    onRemoved = leave,
                    onChooseWallet = leave,
                    onConnectionChanged = {
                        if (selection.wallet.value == RaylWallet.Spark) {
                            connected = it
                            if (it) selection.connected(RaylWallet.Spark)
                        }
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
                                WalletChoice(title, body) {
                                    if (selection.wallet.value == null &&
                                        selection.preparation.value == WalletPreparation.Ready
                                    ) {
                                        selection.choose(provider)
                                    }
                                }
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
