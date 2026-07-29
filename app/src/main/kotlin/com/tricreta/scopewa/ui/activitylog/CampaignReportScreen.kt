package com.tricreta.scopewa.ui.activitylog

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.repository.report.CampaignReport
import com.tricreta.scopewa.ui.contacts.ContactFileIo
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeGreen

/**
 * One campaign's result report — architecture doc section 7.
 *
 * The headline is the success rate **of what was attempted**. Skips get their
 * own block below, counted and broken down but never folded into the failure
 * number: skipping an opted-out contact is the product doing its job, and a
 * report that scored it as a miss would push the client toward turning the
 * safety off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignReportScreen(
    campaignId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CampaignReportViewModel = viewModel(
        key = "campaign-report-$campaignId",
        factory = CampaignReportViewModel.factory(campaignId)
    )
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val load by viewModel.load.collectAsState()

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
                title = { Text(load.report?.campaign?.name ?: "Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val report = load.report
        when {
            load.loading -> Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }

            report == null -> Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("That campaign is gone", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Its messages are still in the activity log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> ReportBody(
                report = report,
                busy = uiState.busy,
                onExport = viewModel::requestExport,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ReportBody(
    report: CampaignReport,
    busy: Boolean,
    onExport: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeadlineCard(report)
        TotalsCard(report)

        if (report.skipsByCategory.isNotEmpty()) {
            BreakdownCard(
                title = "Why ${report.skipped} were skipped",
                body = "Skips are the safety layers working. None of these count against " +
                    "the success rate.",
                rows = report.skipsByCategory.map { it.category.label to it.count }
            )
        }

        if (report.failuresByReason.isNotEmpty()) {
            BreakdownCard(
                title = "Why ${report.failed} failed",
                body = "These are worth acting on — a failure means WhatsApp was reached " +
                    "and the message still didn't go.",
                rows = report.failuresByReason.map { it.reason to it.count }
            )
        }

        report.campaign.pauseReason?.takeIf { it.isNotBlank() }?.let { reason ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("The run was paused", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ScopeAmber
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onExport(false) },
                enabled = !busy
            ) { Text("Export report") }
            OutlinedButton(
                onClick = { onExport(true) },
                enabled = !busy
            ) { Text("Export as text") }
        }
    }
}

@Composable
private fun HeadlineCard(report: CampaignReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (report.attempted == 0) "Nothing was attempted" else "${report.successPercent}%",
                style = MaterialTheme.typography.displaySmall,
                color = if (report.successPercent >= HEALTHY_PERCENT) ScopeGreen else ScopeAmber
            )
            Text(
                text = "${report.sent} sent of ${report.attempted} attempted",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LinearProgressIndicator(
                progress = { report.completionPercent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Text(
                text = "${report.completionPercent}% of the queue dealt with" +
                    if (report.pending > 0) " — ${report.pending} still queued" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TotalsCard(report: CampaignReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            report.campaign.listName?.let { StatRow("List", it) }
            report.campaign.templateName?.let { StatRow("Template", it) }
            StatRow("Status", report.campaign.status)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("Queued", report.queued.toString())
            StatRow("Sent", report.sent.toString())
            StatRow("Failed", report.failed.toString())
            StatRow("Skipped", report.skipped.toString())
            report.uniquenessPercent?.let {
                StatRow("Uniqueness", "$it% of sent messages were unique")
            }
        }
    }
}

@Composable
private fun BreakdownCard(title: String, body: String, rows: List<Pair<String, Int>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            rows.forEach { (label, count) -> StatRow(label, count.toString()) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Below this, the headline goes amber — worth the client looking at. */
private const val HEALTHY_PERCENT = 90

private const val ANY_MIME = "*/*"
