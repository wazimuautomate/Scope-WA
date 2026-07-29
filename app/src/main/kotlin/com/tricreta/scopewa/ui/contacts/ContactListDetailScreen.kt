package com.tricreta.scopewa.ui.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One contact list: its members, plus the bulk-select picker for adding more —
 * reference screenshots 02 → 03.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListDetailScreen(
    listId: Long,
    onBack: () -> Unit,
    onImportInto: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactListDetailViewModel = viewModel(factory = ContactListDetailViewModel.Factory)
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(listId) { viewModel.setListId(listId) }

    val uiState by viewModel.uiState.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val members by viewModel.members.collectAsState()

    var memberQuery by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val selected = remember { mutableStateOf(emptySet<Long>()) }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val export = uiState.pendingExport
        if (uri == null || export == null) {
            viewModel.exportFinished(null)
        } else {
            val written = ContactFileIo.writeText(context, uri, export.content)
            viewModel.exportFinished(
                if (written.isSuccess) "Saved ${export.fileName}." else "Couldn't save that file."
            )
        }
    }

    LaunchedEffect(uiState.pendingExport) {
        uiState.pendingExport?.let { saveFile.launch(it.fileName) }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(summary?.name ?: "List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename list")
                    }
                    ExportMenuButton(
                        enabled = (summary?.contactCount ?: 0) > 0 && !uiState.busy,
                        onExport = { viewModel.requestExport(it) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showPicker = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add contacts") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text(
                text = "${summary?.contactCount ?: 0} contacts" +
                    (summary?.purpose?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { onImportInto(listId) }) { Text("Import a file into this list") }
            }

            ContactSearchField(
                value = memberQuery,
                onValueChange = {
                    memberQuery = it
                    viewModel.setMemberQuery(it)
                },
                placeholder = "Search this list",
                modifier = Modifier.padding(16.dp)
            )

            if (selected.value.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${selected.value.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            viewModel.removeFromList(selected.value.toList())
                            selected.value = emptySet()
                        }
                    ) { Text("Remove from list") }
                }
            }

            if (members.isEmpty()) {
                EmptyState(
                    title = "This list is empty",
                    body = "Add contacts you already have, or import a file straight into it."
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(members, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        checked = contact.id in selected.value,
                        onClick = {
                            selected.value = if (contact.id in selected.value) {
                                selected.value - contact.id
                            } else {
                                selected.value + contact.id
                            }
                        }
                    )
                }
            }
        }
    }

    if (showPicker) {
        ContactPickerDialog(
            viewModel = viewModel,
            onDismiss = { showPicker = false },
            onAdd = { ids ->
                showPicker = false
                viewModel.addToList(ids)
            }
        )
    }

    if (showRename) {
        NewListDialog(
            title = "Rename list",
            initialName = summary?.name.orEmpty(),
            initialPurpose = summary?.purpose.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { name, purpose ->
                showRename = false
                viewModel.rename(name, purpose)
            }
        )
    }
}

/**
 * The bulk-tick picker from reference screenshot 03 — "824 selected" in the
 * header, a checkbox per row, Cancel / Add at the bottom. Only shows contacts
 * that aren't already in the list.
 */
@Composable
private fun ContactPickerDialog(
    viewModel: ContactListDetailViewModel,
    onDismiss: () -> Unit,
    onAdd: (List<Long>) -> Unit
) {
    val pickable by viewModel.pickable.collectAsState()
    var query by remember { mutableStateOf("") }
    val checked: SnapshotStateList<Long> = remember { emptyList<Long>().toMutableStateList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (checked.isEmpty()) "Add contacts" else "${checked.size} selected",
                color = if (checked.isEmpty()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        },
        text = {
            Column {
                ContactSearchField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.setPickerQuery(it)
                    },
                    placeholder = "Search contacts"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val missing = pickable.map { it.id }.filterNot { it in checked }
                            checked.addAll(missing)
                        }
                    ) { Text("Select all shown") }
                    TextButton(onClick = { checked.clear() }) { Text("Clear") }
                }

                if (pickable.isEmpty()) {
                    EmptyState(
                        title = "Nothing to add",
                        body = "Every contact matching that search is already in this list."
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(pickable, key = { it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            checked = contact.id in checked,
                            onClick = {
                                if (contact.id in checked) checked.remove(contact.id)
                                else checked.add(contact.id)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(checked.toList()) }, enabled = checked.isNotEmpty()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
