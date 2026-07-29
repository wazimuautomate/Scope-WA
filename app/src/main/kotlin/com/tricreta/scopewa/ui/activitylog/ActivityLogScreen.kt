package com.tricreta.scopewa.ui.activitylog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.db.dao.ANY_CAMPAIGN
import com.tricreta.scopewa.data.db.dao.ActivityLogEntry
import com.tricreta.scopewa.data.db.entity.MessageStatus
import com.tricreta.scopewa.ui.contacts.ContactFileIo
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The activity log from architecture doc section 7 — every message that has an
 * outcome, newest first, filterable and exportable.
 *
 * The log is the app's receipt. When the client asks "did Mary get it?" this is
 * the screen that answers, which is why a row shows the *rendered* text rather
 * than the template it came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    onOpenReport: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityLogViewModel = viewModel(factory = ActivityLogViewModel.Factory)
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val matchingCount by viewModel.matchingCount.collectAsState()
    val campaigns by viewModel.campaigns.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }

    // One contract for the one format the log exports. Same shape as the
    // Contacts export: the file name carries the extension, which is what the
    // picker and every other app actually read.
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
                title = { Text("Activity log") },
                actions = {
                    TextButton(
                        onClick = viewModel::requestExport,
                        enabled = matchingCount > 0 && !uiState.busy
                    ) { Text("Export CSV") }

                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Campaign reports")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (campaigns.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No campaigns yet") },
                                    onClick = { menuOpen = false },
                                    enabled = false
                                )
                            }
                            campaigns.forEach { campaign ->
                                DropdownMenuItem(
                                    text = { Text("Report: ${campaign.name}") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenReport(campaign.id)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            StatusFilterRow(
                selected = filter.status,
                onSelect = viewModel::setStatusFilter
            )

            CampaignFilterRow(
                selectedCampaignId = filter.campaignId,
                campaignNames = campaigns.associate { it.id to it.name },
                onSelect = viewModel::setCampaignFilter,
                onOpenReport = onOpenReport
            )

            if (entries.isEmpty()) {
                EmptyLog(hasAnyCampaign = campaigns.isNotEmpty())
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    ActivityRow(entry)
                    HorizontalDivider()
                }

                if (entries.size < matchingCount) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(onClick = viewModel::showMore) {
                                Text("Show more (${entries.size} of $matchingCount)")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusFilterRow(
    selected: ActivityStatusFilter,
    onSelect: (ActivityStatusFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActivityStatusFilter.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.label) }
            )
        }
    }
}

@Composable
private fun CampaignFilterRow(
    selectedCampaignId: Long,
    campaignNames: Map<Long, String>,
    onSelect: (Long) -> Unit,
    onOpenReport: (Long) -> Unit
) {
    if (campaignNames.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedCampaignId == ANY_CAMPAIGN,
            onClick = { onSelect(ANY_CAMPAIGN) },
            label = { Text("Every campaign") }
        )
        campaignNames.forEach { (id, name) ->
            FilterChip(
                selected = selectedCampaignId == id,
                onClick = { onSelect(id) },
                label = { Text(name) }
            )
        }
        // The report for whatever is filtered — one tap from the rows it
        // summarises, rather than only from the overflow menu.
        if (selectedCampaignId != ANY_CAMPAIGN) {
            AssistChip(
                onClick = { onOpenReport(selectedCampaignId) },
                label = { Text("See the report") }
            )
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityLogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val status = MessageStatus.fromName(entry.status)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName.ifBlank { entry.phoneE164 },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = buildString {
                        append(entry.phoneE164)
                        entry.campaignName?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
                        formatTime(entry.sentAt)?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(status)
        }

        entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (entry.renderedText.isNotBlank()) {
            Text(
                text = entry.renderedText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Sent green, Failed the theme's error red, Skipped amber. Skipped is
 * deliberately *not* red: a skip is the anti-ban system working, and colouring
 * it as damage would train the client to try and "fix" it.
 */
@Composable
private fun StatusChip(status: MessageStatus) {
    val color: Color = when (status) {
        MessageStatus.Sent -> ScopeGreen
        MessageStatus.Failed -> MaterialTheme.colorScheme.error
        MessageStatus.Skipped -> ScopeAmber
        MessageStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.name,
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}

@Composable
private fun EmptyLog(hasAnyCampaign: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasAnyCampaign) "Nothing matches that filter" else "Nothing sent yet",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = if (hasAnyCampaign) {
                "Try a different status, or pick Every campaign."
            } else {
                "Run a campaign and every message lands here — sent, failed and skipped alike."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private val ROW_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun formatTime(atMillis: Long?): String? =
    atMillis?.let { ROW_TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }

private const val ANY_MIME = "*/*"
