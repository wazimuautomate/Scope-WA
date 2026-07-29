package com.tricreta.scopewa.ui.groupadd

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.brain.groupadd.AddProvenance
import com.tricreta.scopewa.brain.groupadd.GroupAddPacing
import com.tricreta.scopewa.brain.groupadd.GroupAddStopReason
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import com.tricreta.scopewa.data.db.entity.GroupAddStatus
import com.tricreta.scopewa.jobrunner.GroupAddPhase
import com.tricreta.scopewa.jobrunner.GroupAddSnapshot
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeRed

/**
 * Live progress for a group-add run, with the four result buckets from
 * architecture doc section 7.
 *
 * The **needs-invite** bucket gets its own section and its own export, because
 * it is the one result that requires the client to do something by hand.
 * Architecture doc section 8 is explicit that a privacy block is "not a bug" —
 * so this screen must not present it as a failure to be retried, or he will
 * spend an afternoon retrying people who can never be added.
 */
@Composable
fun GroupAddRunningScreen(
    jobId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupAddRunningViewModel = viewModel(factory = GroupAddRunningViewModel.Factory)
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(jobId) { viewModel.setJobId(jobId) }

    val uiState by viewModel.uiState.collectAsState()
    val load by viewModel.job.collectAsState()
    val run by viewModel.run.collectAsState()

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val job = load.job
    val status = GroupAddStatus.fromName(job?.status)

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
                    Text(
                        job?.targetGroup?.ifBlank { "Group add" } ?: "Group add",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        bottomBar = {
            if (job != null && !status.isFinished) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.pause() },
                            enabled = status == GroupAddStatus.Running,
                            modifier = Modifier.weight(1f)
                        ) { Text("Pause") }

                        OutlinedButton(
                            onClick = { viewModel.stop() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop", color = ScopeRed) }
                    }
                }
            }
        }
    ) { padding ->
        if (job == null) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!load.loading) {
                    Text("Job not found", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onBack) { Text("Go back") }
                }
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item { ProgressSummary(job, Modifier.padding(16.dp)) }
            item { StatusCard(job, status, run, Modifier.padding(horizontal = 16.dp)) }

            if (job.needsInvite.isNotEmpty()) {
                item {
                    NeedsInviteCard(
                        job = job,
                        onShare = {
                            val text = viewModel.inviteExport(job)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Needs an invite link — ${job.targetGroup}")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(send, "Share the invite list")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(job.needsInvite) { number ->
                    ResultRow(job.labelFor(number), number, "Send them the link yourself", ScopeAmber)
                }
            }

            if (job.added.isNotEmpty()) {
                item { SectionHeader("Added (${job.added.size})") }
                items(job.added) { number ->
                    ResultRow(
                        title = job.labelFor(number),
                        subtitle = number,
                        detail = AddProvenance.fromCode(job.provenance[number]).label,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (job.failed.isNotEmpty()) {
                item { SectionHeader("Failed (${job.failed.size})") }
                items(job.failed.keys.toList()) { number ->
                    ResultRow(
                        title = job.labelFor(number),
                        subtitle = number,
                        detail = job.failed[number].orEmpty(),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (job.skipped.isNotEmpty()) {
                item { SectionHeader("Skipped (${job.skipped.size})") }
                items(job.skipped.keys.toList()) { number ->
                    ResultRow(
                        title = job.labelFor(number),
                        subtitle = number,
                        detail = job.skipped[number].orEmpty(),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (job.pending.isNotEmpty()) {
                item { SectionHeader("Still queued (${job.pending.size})") }
                items(job.pending) { number ->
                    ResultRow(
                        title = job.labelFor(number),
                        subtitle = number,
                        detail = AddProvenance.fromCode(job.provenance[number]).label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---- progress --------------------------------------------------------------

@Composable
private fun ProgressSummary(job: GroupAddJobEntity, modifier: Modifier = Modifier) {
    val total = job.totalPlanned
    val fraction = if (total <= 0) 0f else (job.totalHandled.toFloat() / total).coerceIn(0f, 1f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("${job.totalHandled} of $total handled", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat("Added", job.addedCount, MaterialTheme.colorScheme.primary)
            Stat("Needs invite", job.needsInviteCount, ScopeAmber)
            Stat(
                label = "Failed",
                value = job.failedCount,
                tint = if (job.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Stat("Skipped", job.skippedCount, MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            "${job.addedToday} of ${GroupAddPacing.DAILY_CAP} added today  ·  " +
                "${job.skippedColdCount} cold numbers were never offered",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Stat(label: String, value: Int, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = tint
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusCard(
    job: GroupAddJobEntity,
    status: GroupAddStatus,
    snapshot: GroupAddSnapshot?,
    modifier: Modifier = Modifier
) {
    val phase = snapshot?.phase ?: GroupAddPhase.Idle
    val seconds = snapshot?.secondsUntilNext ?: 0

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                status == GroupAddStatus.Paused || phase == GroupAddPhase.Paused -> {
                    val (headline, whatToDo) = explainStop(job.stopReason)
                    Text(headline, style = MaterialTheme.typography.titleMedium, color = ScopeAmber)
                    Text(whatToDo, style = MaterialTheme.typography.bodyMedium)
                }

                status.isFinished || phase == GroupAddPhase.Finished -> {
                    Text("Finished", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${job.addedCount} added, ${job.needsInviteCount} need an invite link, " +
                            "${job.failedCount} failed.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (job.pending.isNotEmpty()) {
                        Text(
                            "${job.pending.size} are still queued — today's cap of " +
                                "${GroupAddPacing.DAILY_CAP} stopped the run. Start it again " +
                                "tomorrow and it picks up where it left off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                phase == GroupAddPhase.OpeningGroup ->
                    Text("Opening ${job.targetGroup}…", style = MaterialTheme.typography.titleMedium)

                phase == GroupAddPhase.Adding -> {
                    Text(
                        "Adding ${snapshot?.currentPerson ?: "the next person"}…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Keep the phone unlocked and on this screen. Don't touch WhatsApp while " +
                            "this runs — a stray tap lands in the middle of the flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                phase == GroupAddPhase.Waiting -> {
                    Text("Next person in ${seconds}s", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Every gap is drawn fresh between ${GroupAddPacing.MIN_DELAY_SECONDS} and " +
                            "${GroupAddPacing.MAX_DELAY_SECONDS} seconds. A steady interval is " +
                            "itself a fingerprint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                phase == GroupAddPhase.Cooldown -> {
                    Text("Break — back in ${seconds}s", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "After every ${GroupAddPacing.BATCH_SIZE} people the app stops for " +
                            "${GroupAddPacing.MIN_COOLDOWN_MINUTES}–" +
                            "${GroupAddPacing.MAX_COOLDOWN_MINUTES} minutes, because someone " +
                            "adding friends by hand would.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                status == GroupAddStatus.Running ->
                    Text("Starting…", style = MaterialTheme.typography.titleMedium)

                else -> Text("Not started", style = MaterialTheme.typography.titleMedium)
            }

            if (snapshot != null && snapshot.batchCount > 0) {
                Text(
                    "Batch ${snapshot.batchNumber} of ${snapshot.batchCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            job.lastFailure?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Last problem: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** A stop reason in the client's words: what happened, and what to do next. */
private fun explainStop(reason: String?): Pair<String, String> {
    val known = GroupAddStopReason.entries.firstOrNull { it.name == reason }
    return when (known) {
        GroupAddStopReason.ConsecutiveFailures ->
            "Two adds in a row failed" to
                "That usually means WhatsApp's screen changed, or you aren't an admin of this " +
                "group. Open WhatsApp and add one person by hand. If that works, resume — if " +
                "it doesn't, fix that first."

        GroupAddStopReason.RestrictionDialogShown ->
            "WhatsApp showed a warning about this account" to
                "Stop for the day. Don't resume. Retrying straight after a warning is what " +
                "turns a warning into a ban."

        GroupAddStopReason.DailyCapReached ->
            "Today's cap of ${GroupAddPacing.DAILY_CAP} is used up" to
                "Nothing more is added today. The queue is saved — start it again tomorrow. " +
                "Going over the cap by hand is the fastest way to lose the number."

        GroupAddStopReason.QueueDrained ->
            "Everyone has been handled" to "Nothing left in the queue."

        GroupAddStopReason.StoppedByUser, null ->
            "Paused" to "Nothing happens until you start it again. The queue is saved."
    }
}

// ---- the invite bucket -----------------------------------------------------

@Composable
private fun NeedsInviteCard(
    job: GroupAddJobEntity,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "${job.needsInvite.size} need an invite link",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Their WhatsApp privacy settings don't allow being added to groups. That is " +
                    "their choice, not an error — the app will never retry them, and retrying " +
                    "by hand won't work either. Copy WhatsApp's group invite link and send it " +
                    "to them instead.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onShare) { Text("Share this list") }
        }
    }
}

// ---- rows ------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
    HorizontalDivider()
}

@Composable
private fun ResultRow(title: String, subtitle: String, detail: String, tint: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (title == subtitle) detail else "$subtitle  ·  $detail",
            style = MaterialTheme.typography.bodySmall,
            color = tint
        )
    }
    HorizontalDivider()
}
