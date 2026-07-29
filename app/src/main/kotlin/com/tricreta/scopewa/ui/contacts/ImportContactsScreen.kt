package com.tricreta.scopewa.ui.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.repository.contacts.ImportPlan
import com.tricreta.scopewa.data.repository.contacts.ImportResult
import com.tricreta.scopewa.data.repository.contacts.RejectReason
import com.tricreta.scopewa.data.repository.contacts.parse.ContactFileParser
import com.tricreta.scopewa.data.repository.contacts.parse.CsvTable

/**
 * Import CSV / VCF / TXT — architecture doc section 7, Contacts screen.
 *
 * Three steps, and the middle two exist to stop silent damage: the user picks
 * which CSV column is the phone number rather than the app guessing, and sees
 * the exact counts (new / already known / duplicate / blocked / unusable)
 * before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportContactsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    preselectedListId: Long? = null,
    viewModel: ImportContactsViewModel = viewModel(factory = ImportContactsViewModel.Factory)
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val state by viewModel.state.collectAsState()
    val lists by viewModel.lists.collectAsState()

    LaunchedEffect(preselectedListId) { viewModel.preselectList(preselectedListId) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = ContactFileIo.displayName(context, uri)
            ContactFileIo.readText(context, uri)
                .onSuccess { viewModel.fileChosen(name, it) }
                .onFailure { viewModel.fileUnreadable(name) }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Import contacts") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }

            when (state.step) {
                ImportStep.PickFile -> PickFileStep(
                    onPick = { pickFile.launch(ContactFileParser.PICKER_MIME_TYPES) }
                )

                ImportStep.MapColumns -> MapColumnsStep(
                    fileName = state.fileName,
                    table = state.table,
                    phoneColumn = state.phoneColumn,
                    nameColumn = state.nameColumn,
                    onPhoneColumn = viewModel::setPhoneColumn,
                    onNameColumn = viewModel::setNameColumn,
                    onContinue = { viewModel.buildPlan() },
                    onStartOver = viewModel::startOver
                )

                ImportStep.Confirm -> ConfirmStep(
                    fileName = state.fileName,
                    plan = state.plan,
                    lists = lists,
                    targetListId = state.targetListId,
                    newListName = state.newListName,
                    onTargetList = viewModel::setTargetList,
                    onNewListName = viewModel::setNewListName,
                    onBack = viewModel::backToColumns,
                    onConfirm = { viewModel.confirm() }
                )

                ImportStep.Done -> DoneStep(
                    result = state.result,
                    onImportAnother = viewModel::startOver,
                    onDone = onDone
                )
            }
        }
    }
}

@Composable
private fun PickFileStep(onPick: () -> Unit) {
    Text("Choose a file", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "CSV, VCF or TXT. Numbers are normalised to +254 unless they already carry a " +
            "country code, duplicates are dropped, and anyone on the blocked list is skipped.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onPick) { Text("Pick a file") }
}

@Composable
private fun MapColumnsStep(
    fileName: String,
    table: CsvTable?,
    phoneColumn: String?,
    nameColumn: String?,
    onPhoneColumn: (String) -> Unit,
    onNameColumn: (String?) -> Unit,
    onContinue: () -> Unit,
    onStartOver: () -> Unit
) {
    if (table == null) return

    Text(fileName, style = MaterialTheme.typography.titleMedium)
    Text(
        text = "${table.rows.size} rows · ${table.headers.size} columns. " +
            "Which column holds the phone number?",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    ColumnPicker(
        label = "Phone number column",
        options = table.headers,
        selected = phoneColumn,
        onSelect = { it?.let(onPhoneColumn) }
    )

    ColumnPicker(
        label = "Name column (optional)",
        options = table.headers,
        selected = nameColumn,
        onSelect = onNameColumn,
        allowNone = true
    )

    phoneColumn?.let { column ->
        val sample = table.rows.take(3).map { it[column].orEmpty() }.filter { it.isNotBlank() }
        if (sample.isNotEmpty()) {
            Text(
                text = "First rows in that column: ${sample.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text(
        text = "Every other column is kept as a variable you can use in message templates.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onContinue, enabled = phoneColumn != null) { Text("Continue") }
        OutlinedButton(onClick = onStartOver) { Text("Pick another file") }
    }
}

@Composable
private fun ConfirmStep(
    fileName: String,
    plan: ImportPlan?,
    lists: List<ContactListSummary>,
    targetListId: Long?,
    newListName: String,
    onTargetList: (Long?) -> Unit,
    onNewListName: (String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    if (plan == null) return

    Text(fileName, style = MaterialTheme.typography.titleMedium)
    Text("${plan.rowsRead} rows read", style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()

    SummaryLine("New contacts", plan.newContacts.size.toString())
    SummaryLine("Already in Contacts", plan.existingContacts.size.toString())
    SummaryLine("Duplicates in the file", plan.duplicatesInFile.toString())
    SummaryLine(
        label = "Blocked — will be skipped",
        value = plan.suppressed.size.toString(),
        emphasis = plan.suppressed.isNotEmpty()
    )
    SummaryLine(
        label = "Unusable rows",
        value = plan.rejected.size.toString(),
        emphasis = plan.rejected.isNotEmpty()
    )

    if (plan.rejected.isNotEmpty()) {
        val reasons = plan.rejected.groupingBy { it.reason }.eachCount()
        Text(
            text = reasons.entries.joinToString("  ·  ") { (reason, count) ->
                "$count ${reason.describe()}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    HorizontalDivider()

    Text("Add them to a list (optional)", style = MaterialTheme.typography.titleSmall)

    ColumnPicker(
        label = "Existing list",
        options = lists.map { it.name },
        selected = lists.firstOrNull { it.id == targetListId }?.name,
        onSelect = { name -> onTargetList(lists.firstOrNull { it.name == name }?.id) },
        allowNone = true
    )

    OutlinedTextField(
        value = newListName,
        onValueChange = onNewListName,
        singleLine = true,
        label = { Text("…or make a new list") },
        placeholder = { Text("e.g. Data Challenge") },
        modifier = Modifier.fillMaxWidth()
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onConfirm, enabled = !plan.isEmpty) {
            Text("Import ${plan.importable.size}")
        }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }

    if (plan.isEmpty) {
        Text(
            text = "Nothing in that file can be imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun DoneStep(
    result: ImportResult?,
    onImportAnother: () -> Unit,
    onDone: () -> Unit
) {
    if (result == null) return

    Text("Imported", style = MaterialTheme.typography.titleMedium)

    SummaryLine("Added to Contacts", result.inserted.toString())
    SummaryLine("Updated", result.updated.toString())
    if (result.listName != null) {
        SummaryLine("Added to \"${result.listName}\"", result.addedToList.toString())
    }
    SummaryLine(
        label = "Skipped — blocked",
        value = result.plan.suppressed.size.toString(),
        emphasis = result.plan.suppressed.isNotEmpty()
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDone) { Text("Done") }
        TextButton(onClick = onImportAnother) { Text("Import another file") }
    }
}

private fun RejectReason.describe(): String = when (this) {
    RejectReason.Blank -> "with no number"
    RejectReason.NotANumber -> "with no digits"
    RejectReason.BadLength -> "too short or too long"
}
