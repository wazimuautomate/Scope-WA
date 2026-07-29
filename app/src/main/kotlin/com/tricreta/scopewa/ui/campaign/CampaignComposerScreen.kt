package com.tricreta.scopewa.ui.campaign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.brain.campaign.PacingProfileCatalog
import com.tricreta.scopewa.brain.campaign.RecipientOrdering
import com.tricreta.scopewa.brain.campaign.SkipReason
import com.tricreta.scopewa.brain.uniqueness.summaryLine
import com.tricreta.scopewa.brain.uniqueness.warningLine
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.data.repository.campaign.CampaignPreview
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeRed

/**
 * The Campaign composer from architecture doc section 7 — "pick list → pick
 * template → pacing profile → schedule → preview → Start", modelled on
 * reference screenshots 08 and 09.
 *
 * The preview block is the reason this screen exists. Everything above it is
 * four taps; the counts, the uniqueness meter and the warm-up cap underneath
 * are the anti-ban product made visible, so none of them are collapsed behind a
 * "details" affordance and none of them are softened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignComposerScreen(
    onBack: () -> Unit,
    onStarted: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CampaignComposerViewModel = viewModel(factory = CampaignComposerViewModel.Factory)
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val templates by viewModel.templates.collectAsState()

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // Cleared before navigating, so returning to this screen doesn't fire the
    // callback a second time and open two Running screens.
    LaunchedEffect(uiState.startedCampaignId) {
        uiState.startedCampaignId?.let { id ->
            viewModel.startHandled()
            onStarted(id)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("New campaign") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = { StartBar(state = uiState, onStart = viewModel::start) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard(number = 1, title = "Who gets it") {
                ListPicker(
                    lists = lists,
                    selectedId = uiState.selectedListId,
                    onSelect = viewModel::selectList
                )
            }

            SectionCard(number = 2, title = "What they get") {
                TemplatePicker(
                    templates = templates,
                    selectedId = uiState.selectedTemplateId,
                    onSelect = viewModel::selectTemplate
                )
            }

            SectionCard(number = 3, title = "How fast") {
                PacingPicker(
                    selected = uiState.pacingProfile,
                    onSelect = viewModel::selectPacingProfile
                )
            }

            SectionCard(number = 4, title = "Which WhatsApp") {
                WaPackagePicker(
                    installed = uiState.installedPackages,
                    selected = uiState.selectedPackage,
                    onSelect = viewModel::selectPackage
                )
            }

            SectionCard(number = 5, title = "When") {
                ScheduleChoice.entries.forEach { choice ->
                    ChoiceRow(
                        selected = uiState.schedule == choice,
                        title = choice.label,
                        subtitle = null,
                        onSelect = { viewModel.selectSchedule(choice) }
                    )
                }
                Text(
                    text = "Sending only happens between 8am and 8pm. A campaign that runs " +
                        "into the evening pauses itself and picks up the next morning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            PreviewSection(
                preview = uiState.preview,
                previewing = uiState.previewing,
                hasSelection = uiState.selectedListId != null && uiState.selectedTemplateId != null
            )
        }
    }
}

// ---- pickers ---------------------------------------------------------------

@Composable
private fun ListPicker(
    lists: List<ContactListSummary>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = lists.firstOrNull { it.id == selectedId }

    if (lists.isEmpty()) {
        Text(
            text = "No lists yet. Import a CSV, VCF or TXT file in Contacts and group the " +
                "numbers into a list first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = selected?.let { "${it.name} · ${it.contactCount} contacts" } ?: "Choose a list",
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        lists.forEach { summary ->
            DropdownMenuItem(
                text = { Text("${summary.name} · ${summary.contactCount} contacts") },
                onClick = {
                    open = false
                    onSelect(summary.id)
                },
                trailingIcon = {
                    if (summary.id == selectedId) Icon(Icons.Default.Check, contentDescription = null)
                }
            )
        }
    }
}

@Composable
private fun TemplatePicker(
    templates: List<TemplateEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = templates.firstOrNull { it.id == selectedId }

    if (templates.isEmpty()) {
        Text(
            text = "No templates yet. Write one in Templates — spintax is what stops 200 " +
                "people getting the same string.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(text = selected?.name ?: "Choose a template", modifier = Modifier.weight(1f))
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        templates.forEach { template ->
            DropdownMenuItem(
                text = { Text(template.name) },
                onClick = {
                    open = false
                    onSelect(template.id)
                },
                trailingIcon = {
                    if (template.id == selectedId) Icon(Icons.Default.Check, contentDescription = null)
                }
            )
        }
    }

    // The raw body, not a render — the rendered messages live in the preview
    // block below, where they can be compared against the uniqueness score.
    selected?.let { template ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = template.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PacingPicker(selected: String, onSelect: (String) -> Unit) {
    var pendingWarning by remember { mutableStateOf<String?>(null) }

    PacingProfileCatalog.names.forEach { name ->
        ChoiceRow(
            selected = selected == name,
            title = name,
            subtitle = PacingProfileCatalog.describe(name),
            onSelect = {
                // Section 7: Fast "shows a red warning before it's allowed on".
                // The warning is a gate, not a footnote — the profile is only
                // applied once the user has read it and said yes.
                if (PacingProfileCatalog.needsWarning(name) && selected != name) {
                    pendingWarning = name
                } else {
                    onSelect(name)
                }
            }
        )
        if (PacingProfileCatalog.needsWarning(name)) {
            Text(
                text = FAST_WARNING,
                style = MaterialTheme.typography.bodySmall,
                color = ScopeRed,
                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)
            )
        }
    }

    pendingWarning?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingWarning = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = ScopeRed) },
            title = { Text("$name pacing raises your ban risk", color = ScopeRed) },
            text = {
                Text(
                    "$FAST_WARNING\n\n" +
                        PacingProfileCatalog.describe(name) +
                        "\n\nUse Normal unless this number is already warmed up and has " +
                        "never had a warning."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingWarning = null
                        onSelect(name)
                    }
                ) { Text("Use $name anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWarning = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WaPackagePicker(
    installed: List<WaPackage>,
    selected: WaPackage?,
    onSelect: (WaPackage) -> Unit
) {
    if (installed.isEmpty()) {
        Text(
            text = "Neither WhatsApp nor WhatsApp Business is installed on this phone. " +
                "Scope WA sends through the WhatsApp that's already here — it can't message " +
                "anyone by itself, so there's nothing to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    installed.forEach { target ->
        ChoiceRow(
            selected = selected == target,
            title = target.displayName,
            subtitle = target.packageName,
            onSelect = { onSelect(target) }
        )
    }
}

// ---- preview ---------------------------------------------------------------

@Composable
private fun PreviewSection(
    preview: CampaignPreview?,
    previewing: Boolean,
    hasSelection: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                if (previewing) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            when {
                preview != null -> PreviewBody(preview)

                previewing -> Text(
                    text = "Working out who gets what…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Text(
                    text = if (hasSelection) {
                        "That template couldn't be read. Pick another one."
                    } else {
                        "Pick a list and a template to see exactly who gets messaged, how " +
                            "unique the messages are, and how many fit inside today's cap."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewBody(preview: CampaignPreview) {
    val skippedByReason = remember(preview) {
        preview.plan.skipped.groupingBy { it.reason }.eachCount()
    }

    Text(
        text = "${preview.plan.queuedCount} will be messaged",
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        text = "${preview.plan.skippedCount} skipped",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Named reasons, not a bare number: "824 in the list, 790 sent" is only
    // reassuring if the missing 34 are accounted for.
    SkipReason.entries.forEach { reason ->
        val count = skippedByReason[reason] ?: return@forEach
        Text(
            text = "· $count ${reason.label()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    HorizontalDivider()

    Text("Uniqueness", style = MaterialTheme.typography.labelLarge)
    Text(preview.uniqueness.summaryLine(), style = MaterialTheme.typography.bodyMedium)
    preview.uniqueness.warningLine()?.let { warning ->
        Text(
            text = "⚠ $warning",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }

    HorizontalDivider()

    Text("Warm-up", style = MaterialTheme.typography.labelLarge)
    Text(
        text = "Day ${preview.warmUpDay} — today's cap is ${preview.dailyCap}, " +
            "${preview.sentToday} already sent.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (preview.overDailyCap > 0) {
        Text(
            text = "${preview.overDailyCap} of these people won't be reached today. The " +
                "campaign stops at the cap and carries on tomorrow — going over it is the " +
                "fastest way to lose the number.",
            style = MaterialTheme.typography.bodyMedium,
            color = ScopeAmber
        )
    }

    if (preview.renderedSamples.isNotEmpty()) {
        HorizontalDivider()
        Text("What people will actually get", style = MaterialTheme.typography.labelLarge)
        preview.renderedSamples.take(SAMPLES_SHOWN).forEach { sample ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = sample,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

// ---- chrome ----------------------------------------------------------------

@Composable
private fun StartBar(state: CampaignComposerUiState, onStart: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.hasWhatsApp) {
                Text(
                    text = "Install WhatsApp or WhatsApp Business before starting a campaign.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.starting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Building the queue…")
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.schedule == ScheduleChoice.Now) "Start campaign"
                        else "Schedule campaign"
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(number: Int, title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp, bottom = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun SkipReason.label(): String = when (this) {
    SkipReason.OptedOut -> "opted out or blocked — never messaged again"
    SkipReason.WithinCooldown ->
        "messaged in the last ${RecipientOrdering.DEFAULT_COOLDOWN_DAYS} days"
    SkipReason.DuplicateInList -> "the same number listed twice"
}

private const val FAST_WARNING =
    "Fast sends faster than a person plausibly types. On a number that isn't fully warmed " +
        "up this is the profile that gets accounts banned."

/** Enough to see the spintax varying, few enough to fit above the Start button. */
private const val SAMPLES_SHOWN = 3
