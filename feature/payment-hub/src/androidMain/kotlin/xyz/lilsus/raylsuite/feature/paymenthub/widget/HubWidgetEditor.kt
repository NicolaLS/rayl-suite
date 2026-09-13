package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.paymenthub.HubContact
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetEditor
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetField
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.editorValidationError

@Composable
internal fun HubWidgetEditorScreen(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    val editor = state.editor ?: return
    val variant = state.selectedVariant ?: return
    val definition = state.selectedDefinition ?: return
    val choosesContacts = editor.kind == HubWidgetKind.Contacts ||
        editor.kind == HubWidgetKind.Shortcut
    val selectedContacts = editor.contactIds.mapNotNull { id ->
        state.contacts.firstOrNull { it.id == id }
    }
    val validationError = state.editorValidationError()
    var pickingContacts by remember(editor.definitionId, editor.existingWidgetId) {
        mutableStateOf(false)
    }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        // The screen Scaffold consumes system bars; this handles only the remaining keyboard inset.
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    OutlinedTextField(
                        value = editor.title,
                        onValueChange = viewModel::updateTitle,
                        enabled = !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.hub_widget_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (choosesContacts) {
                    items(selectedContacts, key = HubContact::id) { contact ->
                        SelectedContact(contact, enabled = !state.busy) { pickingContacts = true }
                    }
                    item {
                        OutlinedButton(
                            onClick = { pickingContacts = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    R.string.hub_widget_choose_contacts,
                                    variant.capacity
                                )
                            )
                        }
                        Text(
                            stringResource(
                                R.string.hub_widget_selected_count,
                                selectedContacts.size,
                                variant.capacity
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                } else if (editor.kind == HubWidgetKind.Favorites ||
                    editor.kind == HubWidgetKind.Recents
                ) {
                    item {
                        Text(definition.body(), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.hub_widget_recents_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                if (editor.kind == HubWidgetKind.Shortcut) {
                    item { ShortcutFields(editor, viewModel, enabled = !state.busy) }
                }
                definition.fields.forEach { field ->
                    item(key = "field:${field.key}") {
                        RemoteConfigurationField(
                            field,
                            editor.configuration[field.key].orEmpty(),
                            enabled = !state.busy
                        ) { viewModel.updateConfiguration(field.key, it) }
                    }
                }
                if (editor.existingWidgetId != null) {
                    item {
                        TextButton(
                            onClick = { viewModel.removeWidget(editor.existingWidgetId) },
                            enabled = !state.busy
                        ) {
                            Text(
                                stringResource(R.string.hub_configure_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            (state.error ?: validationError)?.let {
                Text(
                    text = it.label(variant.capacity),
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }
            Button(
                onClick = viewModel::saveWidget,
                enabled = !state.busy && validationError == null,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(
                    stringResource(
                        if (editor.existingWidgetId == null) {
                            R.string.hub_widget_add
                        } else {
                            R.string.hub_editor_save
                        }
                    )
                )
            }
        }
    }
    if (pickingContacts) {
        HubContactPicker(state, viewModel, onClose = { pickingContacts = false })
    }
}

@Composable
private fun SelectedContact(contact: HubContact, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            ContactAvatar(contact.title)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contact.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    contact.address.full,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun ShortcutFields(
    editor: HubWidgetEditor,
    viewModel: WidgetHubViewModel,
    enabled: Boolean
) {
    var currencies by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = editor.amountInput,
                enabled = enabled,
                onValueChange = viewModel::updateAmount,
                label = { Text(stringResource(R.string.hub_widget_shortcut_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (
                        CurrencyCatalog.infoFor(editor.currencyCode).fractionDigits == 0
                    ) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Decimal
                    }
                ),
                modifier = Modifier.weight(1f)
            )
            Box {
                OutlinedButton(onClick = {
                    currencies = true
                }, enabled = enabled) { Text(editor.currencyCode) }
                DropdownMenu(expanded = currencies, onDismissRequest = { currencies = false }) {
                    CurrencyCatalog.supportedCodes.forEach { code ->
                        DropdownMenuItem(text = { Text(code) }, onClick = {
                            currencies = false
                            viewModel.selectCurrency(code)
                        })
                    }
                }
            }
        }
        if (editor.currencyCode != "SAT") {
            Text(
                stringResource(R.string.hub_target_amount_fiat_hint, editor.currencyCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = editor.comment,
            enabled = enabled,
            onValueChange = viewModel::updateComment,
            label = { Text(stringResource(R.string.hub_target_comment_label)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RemoteConfigurationField(
    field: HubWidgetField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    if (field.type == "choice") {
        var expanded by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(field.options.firstOrNull { it.id == value }?.label ?: field.label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            expanded = false
                            onChange(option.id)
                        })
                    }
                }
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            enabled = enabled,
            onValueChange = { onChange(field.maxLength?.let(it::take) ?: it) },
            label = { Text(field.label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.type == "phone") KeyboardType.Phone else KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
