package xyz.lilsus.rayl.providers.blink.feature.walletconnection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.R
import xyz.lilsus.rayl.providers.blink.ui.blinkErrorMessageFor
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton

/** Android renderer for the Blink-owned Blink connection flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlinkWalletScreen(
    state: AddBlinkWalletUiState,
    onBack: (() -> Unit)?,
    onShowInstructions: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    var apiKeyVisible by remember { mutableStateOf(false) }
    val submitOrClearFocus = {
        focusManager.clearFocus(force = true)
        if (state.canSubmit) {
            onSubmit()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.add_blink_wallet_title)) },
                navigationIcon = {
                    onBack?.let { BackIconButton(onClick = it) }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = submitOrClearFocus,
                    enabled = state.canSubmit,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .widthIn(max = 608.dp)
                        .fillMaxWidth()
                        .padding(24.dp)
                        .heightIn(min = 48.dp)
                        .testTag(BlinkWalletConnectionTestTags.CONNECT_BUTTON)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = stringResource(R.string.add_blink_wallet_connect),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag(BlinkWalletConnectionTestTags.SCREEN),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.add_blink_wallet_description,
                    xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onShowInstructions, enabled = !state.isSaving) {
                Text(stringResource(R.string.add_blink_wallet_instructions))
            }

            Column {
                Text(
                    text = stringResource(R.string.add_blink_wallet_connection_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    privacyPolicyUrl?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.add_blink_wallet_privacy))
                        }
                    }
                    termsUrl?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.add_blink_wallet_terms))
                        }
                    }
                }
            }

            Column {
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = onApiKeyChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(BlinkWalletConnectionTestTags.API_KEY_FIELD),
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_blink_wallet_api_key_label)) },
                    placeholder = {
                        Text(stringResource(R.string.add_blink_wallet_api_key_placeholder))
                    },
                    enabled = !state.isSaving,
                    visualTransformation =
                        if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Go
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onGo = { submitOrClearFocus() },
                            onDone = { submitOrClearFocus() }
                        ),
                    trailingIcon = {
                        val visibilityDescription =
                            stringResource(
                                if (apiKeyVisible) {
                                    R.string.add_blink_wallet_hide_api_key
                                } else {
                                    R.string.add_blink_wallet_show_api_key
                                }
                            )
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector =
                                    if (apiKeyVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                contentDescription = visibilityDescription
                            )
                        }
                    }
                )

                TextButton(
                    onClick = onPaste,
                    enabled = !state.isSaving,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.add_blink_wallet_paste))
                }
            }

            if (state.error != null) {
                Text(
                    text = blinkErrorMessageFor(state.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

object BlinkWalletConnectionTestTags {
    const val SCREEN = "blink_wallet_screen"
    const val API_KEY_FIELD = "blink_wallet_api_key_field"
    const val CONNECT_BUTTON = "blink_wallet_connect_button"
}
