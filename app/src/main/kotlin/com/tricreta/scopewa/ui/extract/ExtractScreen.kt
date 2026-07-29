package com.tricreta.scopewa.ui.extract

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.data.repository.extract.ExtractionFilters
import com.tricreta.scopewa.data.repository.extract.GroupExtraction
import com.tricreta.scopewa.data.repository.extract.SaveExtractionResult
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeGreen

/**
 * Extract — architecture doc section 7, and the flow the client already
 * recognises from screenshot 14: "Click Start → it opens WhatsApp → open group
 * info → scroll → come back → you have all contacts."
 */
@Composable
fun ExtractScreen(
    modifier: Modifier = Modifier,
    viewModel: ExtractViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Extract group contacts", style = MaterialTheme.typography.titleLarge)

        if (!state.canExtract) {
            NotReadyCard(hasWhatsApp = state.installedPackages.isNotEmpty())
        }

        HowItWorksCard()

        FiltersCard(
            filters = state.filters,
            onChange = viewModel::setFilters,
            enabled = state.state is ExtractState.Idle
        )

        when (val current = state.state) {
            ExtractState.Idle -> {
                Button(
                    onClick = { viewModel.startExtraction() },
                    enabled = state.canExtract
                ) {
                    Text("Start 10-second countdown")
                }
            }

            is ExtractState.CountingDown -> {
                Text(
                    "Switch to the group's info screen now — reading in ${current.secondsLeft}s",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            is ExtractState.Reading -> ReadingCard(current)

            is ExtractState.Finished -> FinishedCard(
                extraction = current.extraction,
                saved = current.saved,
                onAgain = { viewModel.reset() }
            )

            is ExtractState.Failed -> FailedCard(
                headline = current.headline,
                detail = current.detail,
                diagnostics = current.diagnostics,
                onAgain = { viewModel.reset() },
                onShare = { shareText(context, it) }
            )
        }

        if (history.isNotEmpty()) {
            HorizontalDivider()
            Text("Previous extractions", style = MaterialTheme.typography.titleLarge)
            history.take(HISTORY_SHOWN).forEach { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.groupName, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${entry.memberCount} read · ${entry.importedCount} new contacts" +
                                if (entry.hiddenCount > 0) " · ${entry.hiddenCount} without numbers" else "",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (entry.looksIncomplete) {
                            Text(
                                "WhatsApp reported ${entry.reportedMemberCount} members — " +
                                    "this run didn't reach them all.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ScopeAmber
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotReadyCard(hasWhatsApp: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (hasWhatsApp) "Accessibility permission isn't active" else "WhatsApp not found",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                if (hasWhatsApp) {
                    "Extraction reads WhatsApp's screen, so it needs the Accessibility " +
                        "permission. Finish setup first."
                } else {
                    "Scope WA reads groups from the WhatsApp app on this phone."
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("How this works", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Open the group in WhatsApp and tap its name to open Group info.\n" +
                    "2. Come back here and start the countdown.\n" +
                    "3. Switch to that Group info screen and leave it alone — Scope WA " +
                    "scrolls the participant list and reads it.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nothing is sent and nobody is messaged. Extracted numbers are saved in " +
                    "Scope WA and exported as files — never written to this phone's contacts.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Expect fewer numbers than members. WhatsApp only shows a number for people " +
                    "who aren't already saved on this phone; for everyone else it shows their " +
                    "name instead, and no number can be read.",
                style = MaterialTheme.typography.bodyLarge,
                color = ScopeAmber
            )
        }
    }
}

@Composable
private fun FiltersCard(
    filters: ExtractionFilters,
    onChange: (ExtractionFilters) -> Unit,
    enabled: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            FilterRow("Skip group admins", filters.excludeAdmins, enabled) {
                onChange(filters.copy(excludeAdmins = it))
            }
            FilterRow("Skip people already in my contacts", filters.excludeSaved, enabled) {
                onChange(filters.copy(excludeSaved = it))
            }
            FilterRow(
                "Skip members whose number isn't shown",
                filters.excludeWithoutNumbers,
                enabled
            ) {
                onChange(filters.copy(excludeWithoutNumbers = it))
            }
        }
    }
}

@Composable
private fun FilterRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReadingCard(state: ExtractState.Reading) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Reading participants…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val progress = state.progress
            Text(
                if (progress == null) {
                    "Starting…"
                } else {
                    buildString {
                        append("${progress.membersFound} found")
                        progress.reportedMemberCount?.let { append(" of $it") }
                        append(" · pass ${progress.scrollPasses}")
                    }
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Leave WhatsApp on screen until this finishes.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun FinishedCard(
    extraction: GroupExtraction,
    saved: SaveExtractionResult?,
    onAgain: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ScopeGreen.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(extraction.groupName, style = MaterialTheme.typography.titleLarge, color = ScopeGreen)
            Spacer(Modifier.height(8.dp))

            Text("${extraction.members.size} participants read", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${extraction.withNumbers.size} with a readable number",
                style = MaterialTheme.typography.bodyLarge
            )
            if (extraction.withoutNumbers.isNotEmpty()) {
                Text(
                    "${extraction.withoutNumbers.size} without one (already saved on this " +
                        "phone, or hidden by WhatsApp)",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            saved?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${it.imported} new contacts saved · ${it.alreadyKnown} already known",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (extraction.looksIncomplete) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ WhatsApp says this group has ${extraction.reportedMemberCount} members, " +
                        "but only ${extraction.members.size} rows were read. The list probably " +
                        "didn't finish scrolling — run it again and don't touch the screen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ScopeAmber
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onAgain) { Text("Extract another group") }
        }
    }
}

@Composable
private fun FailedCard(
    headline: String,
    detail: String,
    diagnostics: String?,
    onAgain: () -> Unit,
    onShare: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge)

            diagnostics?.let { dump ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = dump.take(DIAGNOSTICS_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(dump)) }) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = { onShare(dump) }) { Text("Share") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onAgain) { Text("Try again") }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Scope WA extraction diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
}

private const val HISTORY_SHOWN = 10
private const val DIAGNOSTICS_PREVIEW_CHARS = 2_000
