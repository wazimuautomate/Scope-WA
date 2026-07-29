package com.tricreta.scopewa.ui.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.SuppressionEntity

private enum class ContactsTab(val label: String) {
    Lists("Lists"),
    All("All contacts"),
    Blocked("Blocked")
}

/**
 * The Contacts screen from architecture doc section 7 — "Lists with counts,
 * import CSV/VCF/TXT, bulk tick, filters, export", modelled on reference
 * screenshots 02, 03 and 04.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onOpenList: (Long) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory)
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactCount by viewModel.contactCount.collectAsState()
    val optedOut by viewModel.optedOut.collectAsState()
    val suppressed by viewModel.suppressed.collectAsState()
    val query by viewModel.query.collectAsState()

    var tab by remember { mutableStateOf(ContactsTab.Lists) }
    var showNewList by remember { mutableStateOf(false) }
    var showAddNumber by remember { mutableStateOf(false) }
    var listPendingDelete by remember { mutableStateOf<ContactListSummary?>(null) }

    // One contract for every format: the launcher is registered once, so a
    // per-format MIME type wouldn't take effect anyway. The file name carries
    // the extension, which is what the picker and other apps actually read.
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ANY_MIME)
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
                title = { Text("Contacts") },
                actions = {
                    ExportMenuButton(
                        enabled = contactCount > 0 && !uiState.busy,
                        onExport = { viewModel.requestExport(null, "all-contacts", it) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            when (tab) {
                ContactsTab.Lists -> ExtendedFloatingActionButton(
                    onClick = { showNewList = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New list") }
                )

                ContactsTab.All -> ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import file") }
                )

                ContactsTab.Blocked -> ExtendedFloatingActionButton(
                    onClick = { showAddNumber = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Block a number") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                ContactsTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) }
                    )
                }
            }

            when (tab) {
                ContactsTab.Lists -> ListsTab(
                    lists = lists,
                    contactCount = contactCount,
                    onOpenList = onOpenList,
                    onImport = onImport,
                    onDeleteRequest = { listPendingDelete = it }
                )

                ContactsTab.All -> AllContactsTab(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    contacts = contacts,
                    contactCount = contactCount,
                    onToggleBlocked = { viewModel.setOptedOut(it, !it.optedOut) }
                )

                ContactsTab.Blocked -> BlockedTab(
                    suppressed = suppressed,
                    optedOutCount = optedOut.size,
                    onUnblock = { viewModel.unsuppressNumber(it) }
                )
            }
        }
    }

    if (showNewList) {
        NewListDialog(
            onDismiss = { showNewList = false },
            onConfirm = { name, purpose ->
                showNewList = false
                viewModel.createList(name, purpose)
            }
        )
    }

    if (showAddNumber) {
        AddNumberDialog(
            onDismiss = { showAddNumber = false },
            onConfirm = { number ->
                showAddNumber = false
                viewModel.suppressNumber(number)
            }
        )
    }

    listPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { listPendingDelete = null },
            title = { Text("Delete \"${pending.name}\"?") },
            text = {
                Text(
                    "The list goes away. Its ${pending.contactCount} contacts stay in Contacts " +
                        "and in any other list they belong to."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        listPendingDelete = null
                        viewModel.deleteList(pending)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { listPendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ListsTab(
    lists: List<ContactListSummary>,
    contactCount: Int,
    onOpenList: (Long) -> Unit,
    onImport: () -> Unit,
    onDeleteRequest: (ContactListSummary) -> Unit
) {
    if (lists.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = "No lists yet",
                body = if (contactCount == 0) {
                    "Import a CSV, VCF or TXT file to get started."
                } else {
                    "You have $contactCount contacts. Group them into a list to send to."
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onImport) { Text("Import a file") }
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(lists, key = { it.id }) { summary ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenList(summary.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(summary.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = buildString {
                            append("${summary.contactCount} contacts")
                            if (summary.purpose.isNotBlank()) append("  ·  ${summary.purpose}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onDeleteRequest(summary) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${summary.name}")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun AllContactsTab(
    query: String,
    onQueryChange: (String) -> Unit,
    contacts: List<ContactEntity>,
    contactCount: Int,
    onToggleBlocked: (ContactEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ContactSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search $contactCount contacts",
            modifier = Modifier.padding(16.dp)
        )

        if (contacts.isEmpty()) {
            EmptyState(
                title = if (contactCount == 0) "No contacts yet" else "Nothing matches that",
                body = if (contactCount == 0) {
                    "Import a CSV, VCF or TXT file. Numbers are normalised to +254 by default " +
                        "and duplicates are dropped automatically."
                } else {
                    "Try a different name or number."
                }
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(contacts, key = { it.id }) { contact ->
                ContactRow(
                    contact = contact,
                    trailing = {
                        TextButton(onClick = { onToggleBlocked(contact) }) {
                            Text(if (contact.optedOut) "Unblock" else "Block")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BlockedTab(
    suppressed: List<SuppressionEntity>,
    optedOutCount: Int,
    onUnblock: (SuppressionEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Blocked numbers are never messaged, and stay blocked even if the same " +
                "number is imported again. Anyone who replies STOP, ACHA or SITAKI lands " +
                "here automatically once campaigns are running.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )

        if (suppressed.isEmpty()) {
            EmptyState(
                title = "Nobody is blocked",
                body = "$optedOutCount contacts are marked opted out."
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(suppressed, key = { it.phoneE164 }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.phoneE164, style = MaterialTheme.typography.bodyLarge)
                        if (entry.reason.isNotBlank()) {
                            Text(
                                entry.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { onUnblock(entry) }) { Text("Unblock") }
                }
                HorizontalDivider()
            }
        }
    }
}

private const val ANY_MIME = "*/*"
