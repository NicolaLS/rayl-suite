package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.feature.paymenthub.HubContact
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubContactPicker(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    onClose: () -> Unit
) {
    val editor = state.editor ?: return
    val capacity = state.selectedVariant?.capacity ?: return
    val atCapacity = capacity > 1 && editor.contactIds.size >= capacity
    val selected = editor.contactIds.mapNotNull { id -> state.contacts.firstOrNull { it.id == id } }
    val query = state.query.trim()
    val contacts = (selected + state.contacts.filter { it.id !in editor.contactIds }).filter {
        query.isEmpty() || it.title.contains(query, ignoreCase = true) ||
            it.address.full.contains(query, ignoreCase = true)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    var addingContact by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<HubContact?>(null) }
    DisposableEffect(viewModel) {
        viewModel.updateQuery("")
        onDispose { viewModel.updateQuery("") }
    }
    Dialog(
        onDismissRequest = { if (!state.busy) onClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !state.busy,
            dismissOnClickOutside = !state.busy
        )
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.hub_widget_choose_contacts, capacity)) },
                    navigationIcon = {
                        BackIconButton(onClick = { if (!state.busy) onClose() })
                    },
                    actions = {
                        IconButton(
                            onClick = { addingContact = true },
                            enabled = !state.busy && !atCapacity
                        ) {
                            Icon(
                                Icons.Filled.PersonAdd,
                                contentDescription = stringResource(R.string.hub_widget_add_contact)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()
                        .padding(padding).consumeWindowInsets(padding).imePadding()
                ) {
                    // Search remains fixed while results use the space left above the keyboard.
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        enabled = !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.hub_new_search)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Text(
                        stringResource(
                            R.string.hub_widget_selected_count,
                            editor.contactIds.size,
                            capacity
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        ).semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
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
                        items(contacts, key = HubContact::id) { contact ->
                            val index = editor.contactIds.indexOf(contact.id)
                            PickerContact(
                                contact = contact,
                                selected = index >= 0,
                                singleSelection = capacity == 1,
                                selectionEnabled = !state.busy && (index >= 0 || !atCapacity),
                                menuEnabled = !state.busy,
                                onSelect = {
                                    viewModel.toggleContact(contact.id)
                                    if (capacity == 1) onClose()
                                },
                                onDelete = { pendingDeletion = contact },
                                onMoveUp = if (index > 0) {
                                    { viewModel.moveContact(contact.id, -1) }
                                } else {
                                    null
                                },
                                onMoveDown = if (index >= 0 && index < selected.lastIndex) {
                                    { viewModel.moveContact(contact.id, 1) }
                                } else {
                                    null
                                }
                            )
                        }
                        if (atCapacity) {
                            item {
                                Text(
                                    stringResource(R.string.hub_widget_choose_contacts, capacity),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (!addingContact) {
                        state.error?.let {
                            Text(
                                it.label(capacity),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Button(
                        onClick = onClose,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text(stringResource(R.string.hub_canvas_done))
                    }
                }
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
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.hub_canvas_remove_cancel))
                }
            }
        )
    }
}

@Composable
private fun PickerContact(
    contact: HubContact,
    selected: Boolean,
    singleSelection: Boolean,
    selectionEnabled: Boolean,
    menuEnabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
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
                        enabled = selectionEnabled,
                        role = Role.RadioButton,
                        onClick = onSelect
                    )
                } else {
                    Modifier.toggleable(
                        value = selected,
                        enabled = selectionEnabled,
                        role = Role.Checkbox,
                        onValueChange = { onSelect() }
                    )
                }
            ).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
        ) {
            ContactAvatar(
                contact.title,
                modifier = Modifier.alpha(if (selectionEnabled) 1f else 0.45f)
            )
            Column(modifier = Modifier.weight(1f).alpha(if (selectionEnabled) 1f else 0.45f)) {
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
                RadioButton(selected = selected, onClick = null, enabled = selectionEnabled)
            } else {
                Checkbox(checked = selected, onCheckedChange = null, enabled = selectionEnabled)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = menuEnabled) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(R.string.hub_new_more)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onMoveUp?.let { move ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_action_move_up)) },
                            onClick = {
                                menu = false
                                move()
                            }
                        )
                    }
                    onMoveDown?.let { move ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hub_action_move_down)) },
                            onClick = {
                                menu = false
                                move()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hub_contact_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
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
        onDismissRequest = { if (!state.busy) onClose() },
        title = { Text(stringResource(R.string.hub_widget_add_contact)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(enabled = !state.busy, value = title, onValueChange = {
                    title = it
                }, label = {
                    Text(stringResource(R.string.hub_target_name_label))
                }, singleLine = true)
                OutlinedTextField(
                    value = address,
                    enabled = !state.busy,
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
            TextButton(onClick = onClose, enabled = !state.busy) {
                Text(stringResource(R.string.hub_canvas_remove_cancel))
            }
        }
    )
}
