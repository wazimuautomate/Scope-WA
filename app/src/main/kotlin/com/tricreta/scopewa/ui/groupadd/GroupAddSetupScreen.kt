package com.tricreta.scopewa.ui.groupadd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.brain.groupadd.EligibilitySplit
import com.tricreta.scopewa.brain.groupadd.GroupAddPacing
import com.tricreta.scopewa.brain.groupadd.RejectedCandidate
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.ui.theme.ScopeAmber

/**
 * "Pick group → pick list → strict pacing → run" (architecture doc section 7),
 * with one addition the extension this is modelled on does not have: a screen
 * that says out loud how many people were **refused** and why.
 *
 * That refusal panel is the safety feature, not decoration. Section 6 layer 5
 * bans adding cold numbers because it is what gets numbers killed; a screen
 * that quietly shipped 34 of the 200 people the user picked would leave him
 * believing the other 166 were added. So the cold count is given the same
 * visual weight as the eligible one, above the fold, before Start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAddSetupScreen(
    onBack: () -> Unit,
    onStarted: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupAddSetupViewModel = viewModel(factory = GroupAddSetupViewModel.Factory)
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsState()
    val lists by viewModel.lists.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledPackages()
        viewModel.refreshDailyCount()
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LaunchedEffect(uiState.startedJobId) {
        uiState.startedJobId?.let {
            onStarted(it)
            viewModel.startHandled()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Add people to a group", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        onClick = { viewModel.start() },
                        enabled = uiState.canStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (uiState.attemptedToday > 0) {
                                "Add ${uiState.attemptedToday} people"
                            } else {
                                "Add people"
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { RiskBanner() }

            if (!uiState.hasWhatsApp) {
                item { NoWhatsAppCard() }
            }

            item {
                OutlinedTextField(
                    value = uiState.targetGroup,
                    onValueChange = viewModel::setTargetGroup,
                    label = { Text("Group name, exactly as WhatsApp shows it") },
                    supportingText = {
                        Text(
                            "The app searches your chats for this name. A typo means it can't " +
                                "find the group, and you must be an admin of it."
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (uiState.installedPackages.size > 1) {
                item {
                    PackagePicker(
                        installed = uiState.installedPackages,
                        selected = uiState.selectedPackage,
                        onSelect = viewModel::selectPackage
                    )
                }
            }

            item {
                Text("Who to add", style = MaterialTheme.typography.titleMedium)
            }

            if (lists.isEmpty()) {
                item {
                    Text(
                        "No contact lists yet. Import some contacts first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(lists, key = { it.id }) { list ->
                ListRow(
                    list = list,
                    selected = list.id == uiState.selectedListId,
                    onSelect = { viewModel.selectList(list.id) }
                )
            }

            if (uiState.screening) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text("Checking who can safely be added…")
                    }
                }
            }

            uiState.split?.let { split ->
                item { ScreeningSummary(split, uiState) }
                if (split.rejected.isNotEmpty()) {
                    item {
                        Text(
                            "Refused (${split.rejectedCount})",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    items(split.rejected.take(MAX_REJECTIONS_SHOWN)) { rejection ->
                        RejectedRow(rejection)
                    }
                    if (split.rejectedCount > MAX_REJECTIONS_SHOWN) {
                        item {
                            Text(
                                "…and ${split.rejectedCount - MAX_REJECTIONS_SHOWN} more, all for " +
                                    "the same reason.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { PacingSummary(uiState) }
        }
    }
}

// ---- the safety panel ------------------------------------------------------

/**
 * The eligible-versus-refused split, given equal weight on purpose. See the
 * screen's KDoc for why this isn't a footnote.
 */
@Composable
private fun ScreeningSummary(split: EligibilitySplit, uiState: GroupAddSetupUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "${split.eligibleCount} of ${split.totalConsidered} can be added",
                style = MaterialTheme.typography.headlineSmall
            )

            if (split.coldCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = ScopeAmber)
                    Column {
                        Text(
                            "${split.coldCount} are cold numbers and will not be added",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = ScopeAmber
                        )
                        Text(
                            "They have never messaged you and didn't come from a group you " +
                                "already share. Adding strangers to a group is the single " +
                                "fastest way to lose the number, so the app won't do it — " +
                                "not even if you ask.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (split.rejectedCount > split.coldCount) {
                Text(
                    "${split.rejectedCount - split.coldCount} more were left out for other " +
                        "reasons — opted out, or no usable number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (uiState.deferredToTomorrow > 0) {
                Text(
                    "Today's cap is ${GroupAddPacing.DAILY_CAP} and ${uiState.addedToday} have " +
                        "already gone, so ${uiState.attemptedToday} will be added now and " +
                        "${uiState.deferredToTomorrow} wait for tomorrow. They stay in the " +
                        "queue — nothing is lost.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (split.hasNobody) {
                Text(
                    "Nobody on this list is eligible. Pick a list of people who have replied " +
                        "to you, or one extracted from a group you're both already in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RejectedRow(rejection: RejectedCandidate) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(rejection.candidate.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            rejection.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- pacing ----------------------------------------------------------------

@Composable
private fun PacingSummary(uiState: GroupAddSetupUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("How slowly this runs", style = MaterialTheme.typography.titleSmall)
            Text(GroupAddPacing.summary(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "There is no fast option here, and there won't be one. Group adding is the " +
                    "riskiest thing this app does — these numbers come straight from the " +
                    "design and are not settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${uiState.remainingToday} adds left today, across every group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RiskBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column {
                Text(
                    "This is the riskiest thing the app does",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Adding people to groups gets numbers banned faster than sending does. " +
                        "The pacing below reduces that risk. It does not remove it. Use the " +
                        "second number, keep the phone plugged in, and don't touch it while " +
                        "this runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ---- pickers ---------------------------------------------------------------

@Composable
private fun ListRow(list: ContactListSummary, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(list.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${list.contactCount} contacts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackagePicker(
    installed: List<WaPackage>,
    selected: WaPackage?,
    onSelect: (WaPackage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Which WhatsApp", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            installed.forEach { target ->
                FilterChip(
                    selected = target == selected,
                    onClick = { onSelect(target) },
                    label = { Text(target.displayName) }
                )
            }
        }
    }
}

@Composable
private fun NoWhatsAppCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("No WhatsApp on this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "This app drives the WhatsApp already installed here. It never messages or " +
                    "adds anyone by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Enough to show the pattern without turning the screen into a 5,000-row list. */
private const val MAX_REJECTIONS_SHOWN = 20
