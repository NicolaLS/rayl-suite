package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.R

/** Shared connection chrome; providers own credentials, validation and connection work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialConnectionScreen(
    title: String,
    description: String,
    actionTitle: String,
    canSubmit: Boolean,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)?,
    onShowInstructions: (() -> Unit)?,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    instructionsTitle: String? = null,
    connectionNotice: String? = null,
    termsTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { BackIconButton(onClick = it) } }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        if (canSubmit) onSubmit()
                    },
                    enabled = canSubmit,
                    shape = RoundedCornerShape(16.dp),
                    modifier = actionModifier.widthIn(max = 608.dp).fillMaxWidth()
                        .padding(24.dp).heightIn(min = 48.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(actionTitle, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 608.dp)
                    .fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                onShowInstructions?.let {
                    TextButton(onClick = it, enabled = !isSubmitting) {
                        Text(instructionsTitle ?: stringResource(R.string.credential_instructions))
                    }
                }
                Column {
                    Text(
                        connectionNotice ?: stringResource(R.string.credential_connection_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        privacyPolicyUrl?.let { url ->
                            TextButton(
                                onClick = { uriHandler.openUri(url) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.credential_privacy))
                            }
                        }
                        termsUrl?.let { url ->
                            TextButton(
                                onClick = { uriHandler.openUri(url) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(termsTitle ?: stringResource(R.string.credential_terms))
                            }
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun CredentialInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean = true,
    multiline: Boolean = false,
    fieldModifier: Modifier = Modifier,
    pasteModifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            enabled = enabled,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus(force = true)
                    onSubmit()
                }
            ),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = enabled) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = stringResource(
                            if (visible) R.string.credential_hide else R.string.credential_show
                        )
                    )
                }
            }
        )
        TextButton(
            onClick = onPaste,
            enabled = enabled,
            modifier = pasteModifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.credential_paste))
        }
    }
}
