package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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

@Composable
internal fun HubWidgetEditorScreen(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    val editor = state.editor ?: return
    val variant = state.selectedVariant ?: return
    val definition = state.selectedDefinition ?: return
    val choosesContacts =
        editor.kind == HubWidgetKind.Contacts || editor.kind == HubWidgetKind.Shortcut
    val selectionFits = editor.contactIds.size in 1..variant.capacity
    var addingContact by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<HubContact?>(null) }
    val contacts = state.contacts.filter {
        state.query.isBlank() || it.title.contains(state.query, ignoreCase = true) ||
            it.address.full.contains(state.query, ignoreCase = true)
    }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text(definition.label(), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        definition.body(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    OutlinedTextField(
                        value = editor.title,
                        onValueChange = viewModel::updateTitle,
                        singleLine = true,
                        label = { Text(stringResource(R.string.hub_widget_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (definition.variants.size > 1) {
                    item {
                        OutlinedButton(onClick = {
                            viewModel.back()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.hub_widget_select_variant) + ": " +
                                    (variant.title ?: variant.label())
                            )
                        }
                    }
                }
                if (editor.kind == HubWidgetKind.Shortcut) {
                    item { ShortcutFields(editor, viewModel) }
                }
                definition.fields.forEach { field ->
                    item(key = "field:${field.key}") {
                        RemoteConfigurationField(field, editor.configuration[field.key].orEmpty()) {
                            viewModel.updateConfiguration(field.key, it)
                        }
                    }
                }
                if (choosesContacts) {
                    item {
                        Text(
                            stringResource(R.string.hub_widget_choose_contacts, variant.capacity),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(
                                R.string.hub_widget_selected_count,
                                editor.contactIds.size,
                                variant.capacity
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (editor.contactIds.size > variant.capacity) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    if (editor.contactIds.size > 1) {
                        item {
                            Column {
                                editor.contactIds.mapNotNull { id ->
                                    state.contacts.firstOrNull {
                                        it.id ==
                                            id
                                    }
                                }
                                    .forEachIndexed { index, contact ->
                                        SelectedContact(
                                            contact,
                                            index,
                                            editor.contactIds.size,
                                            viewModel
                                        )
                                    }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                addingContact = true
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.hub_widget_add_contact))
                            }
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = viewModel::updateQuery,
                                singleLine = true,
                                label = { Text(stringResource(R.string.hub_new_search)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (contacts.isEmpty()) {
                        item {
                            Text(
                                stringResource(
                                    if (state.contacts.isEmpty()) {
                                        R.string.hub_new_no_contacts
                                    } else {
                                        R.string.hub_new_no_matches
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(contacts, key = { "contact:${it.id}" }) { contact ->
                        PickerContact(
                            contact = contact,
                            selected = contact.id in editor.contactIds,
                            singleSelection = editor.kind == HubWidgetKind.Shortcut,
                            enabled = !state.busy && (
                                contact.id in editor.contactIds ||
                                    editor.contactIds.size < variant.capacity ||
                                    editor.kind == HubWidgetKind.Shortcut
                                ),
                            onSelect = { viewModel.toggleContact(contact.id) },
                            onDelete = { pendingDeletion = contact }
                        )
                    }
                } else if (editor.kind == HubWidgetKind.Favorites ||
                    editor.kind == HubWidgetKind.Recents
                ) {
                    item {
                        Text(
                            stringResource(R.string.hub_widget_recents_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (editor.existingWidgetId != null) {
                    item {
                        TextButton(onClick = { viewModel.removeWidget(editor.existingWidgetId) }) {
                            Text(
                                stringResource(R.string.hub_configure_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            state.error?.let {
                Text(
                    text = it.label(variant.capacity),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                        liveRegion =
                            LiveRegionMode.Assertive
                    }
                )
            }
            Button(
                onClick = viewModel::saveWidget,
                enabled = !state.busy && (!choosesContacts || selectionFits),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(
                    stringResource(
                        if (editor.existingWidgetId ==
                            null
                        ) {
                            R.string.hub_widget_add
                        } else {
                            R.string.hub_editor_save
                        }
                    )
                )
            }
        }
    }
    if (addingContact) {
        AddContactDialog(state, viewModel, onClose = { addingContact = false })
    }
    pendingDeletion?.let { contact ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.hub_contact_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(contact.title, style = MaterialTheme.typography.titleMedium)
                    Text(contact.address.full)
                    Text(stringResource(R.string.hub_contact_delete_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(contact.id)
                    pendingDeletion = null
                }) {
                    Text(
                        stringResource(R.string.hub_contact_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                }) { Text(stringResource(R.string.hub_canvas_remove_cancel)) }
            }
        )
    }
}

@Composable
private fun PickerContact(
    contact: HubContact,
    selected: Boolean,
    singleSelection: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().then(
                if (singleSelection) {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onSelect
                    )
                } else {
                    Modifier.toggleable(
                        value = selected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onSelect() }
                    )
                }
            )
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (singleSelection) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
            } else {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(R.string.hub_new_more)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = {
                        Text(stringResource(R.string.hub_contact_delete))
                    }, onClick = {
                        menu =
                            false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
private fun SelectedContact(
    contact: HubContact,
    index: Int,
    count: Int,
    viewModel: WidgetHubViewModel
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            contact.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { viewModel.moveContact(contact.id, -1) }, enabled = index > 0) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.hub_action_move_up)
            )
        }
        IconButton(onClick = {
            viewModel.moveContact(contact.id, 1)
        }, enabled = index < count - 1) {
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = stringResource(R.string.hub_action_move_down)
            )
        }
    }
}

@Composable
private fun ShortcutFields(editor: HubWidgetEditor, viewModel: WidgetHubViewModel) {
    var currencies by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = editor.amountInput,
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
                OutlinedButton(onClick = { currencies = true }) { Text(editor.currencyCode) }
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
    onChange: (String) -> Unit
) {
    if (field.type == "choice") {
        var expanded by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun AddContactDialog(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val savedSerial = remember { state.contactSavedSerial }
    LaunchedEffect(state.contactSavedSerial) {
        if (state.contactSavedSerial != savedSerial) onClose()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.hub_widget_add_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = {
                    title = it
                }, label = {
                    Text(stringResource(R.string.hub_target_name_label))
                }, singleLine = true)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.hub_target_address_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )
                if (submitted) {
                    state.error?.let {
                        Text(
                            it.label(state.selectedVariant?.capacity ?: 1),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    viewModel.addContact(title, address)
                },
                enabled =
                    !state.busy && title.isNotBlank() && address.isNotBlank()
            ) {
                Text(stringResource(R.string.hub_widget_save_contact))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.hub_canvas_remove_cancel))
            }
        }
    )
}
