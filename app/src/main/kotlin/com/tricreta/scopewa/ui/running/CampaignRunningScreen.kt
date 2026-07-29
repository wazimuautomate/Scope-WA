package com.tricreta.scopewa.ui.running

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.brain.safety.PauseReason
import com.tricreta.scopewa.data.db.dao.CampaignProgress
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignStatus
import com.tricreta.scopewa.data.db.entity.MessageStatus
import com.tricreta.scopewa.jobrunner.RunPhase
import com.tricreta.scopewa.jobrunner.RunSnapshot
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeRed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Running screen from architecture doc section 7 — "live progress,
 * sent/failed/skipped, current person, next-in countdown, Pause / Resume /
 * Stop", modelled on the extension's side panel.
 *
 * The countdown and the pause explanations are the product, not chrome. The
 * client is paying for the app to go slowly and to stop when WhatsApp complains
 * (section 6, layers 2 and 4); a screen that hid that would look identical to
 * the tools that get numbers banned. So the waiting state says out loud that
 * the gap is random, and every auto-pause says what happened *and* what to do
 * about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignRunningScreen(
    campaignId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CampaignRunningViewModel = viewModel(factory = CampaignRunningViewModel.Factory)
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(campaignId) { viewModel.setCampaignId(campaignId) }

    val uiState by viewModel.uiState.collectAsState()
    val load by viewModel.campaign.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val recipients by viewModel.recipients.collectAsState()
    val run by viewModel.run.collectAsState()

    var confirmStop by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val campaign = load.campaign
    val status = CampaignStatus.fromName(campaign?.status)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(campaign?.name ?: "Campaign") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (campaign != null) {
                ControlBar(
                    status = status,
                    startedBefore = campaign.startedAt != null,
                    onPause = { viewModel.pause() },
                    onResume = { viewModel.resume() },
                    onStop = { confirmStop = true }
                )
            }
        }
    ) { padding ->
        if (campaign == null) {
            // Deleted from under us, or a stale link. Nothing to control and
            // nothing to show — say so rather than rendering zeroed counters
            // that look like a campaign which sent nothing.
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!load.loading) {
                    Text("Campaign not found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "It was deleted, or it never existed. Nothing is running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onBack) { Text("Go back") }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item {
                ProgressSummary(
                    progress = progress,
                    campaign = campaign,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                StatusCard(
                    campaign = campaign,
                    status = status,
                    snapshot = run,
                    progress = progress,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                Text(
                    text = "Recipients",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                )
                HorizontalDivider()
            }

            if (recipients.isEmpty()) {
                item {
                    Text(
                        text = "This campaign has no recipients queued.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(recipients, key = { it.id }) { row ->
                RecipientListRow(row)
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop this campaign?") },
            text = {
                Text(
                    "Stopping is final — a stopped campaign can't be resumed, and the " +
                        "${progress.pending} people still queued will never get the message.\n\n" +
                        "If you only want to hold it for a while, use Pause instead. A paused " +
                        "campaign keeps its place and carries on exactly where it stopped."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        viewModel.stop()
                    }
                ) { Text("Stop for good") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Keep going") }
            }
        )
    }
}

// ---- progress ------------------------------------------------------------

@Composable
private fun ProgressSummary(
    progress: CampaignProgress,
    campaign: CampaignEntity,
    modifier: Modifier = Modifier
) {
    val done = progress.sent + progress.failed + progress.skipped
    val fraction = if (progress.total <= 0) 0f else (done.toFloat() / progress.total).coerceIn(0f, 1f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "$done of ${progress.total} done",
            style = MaterialTheme.typography.headlineSmall
        )

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat("Sent", progress.sent, MaterialTheme.colorScheme.primary)
            Stat(
                label = "Failed",
                value = progress.failed,
                tint = if (progress.failed > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Stat("Skipped", progress.skipped, MaterialTheme.colorScheme.onSurfaceVariant)
            Stat("Pending", progress.pending, MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The warm-up ramp is enforced, not advisory — architecture doc section
        // 6 layer 2. Showing the day and today's count is how the client learns
        // why a campaign stops short of its list size.
        Text(
            text = "Day ${campaign.warmUpDay}  ·  ${campaign.sentToday} sent today",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Stat(label: String, value: Int, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = tint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- live status ---------------------------------------------------------

@Composable
private fun StatusCard(
    campaign: CampaignEntity,
    status: CampaignStatus,
    snapshot: RunSnapshot?,
    progress: CampaignProgress,
    modifier: Modifier = Modifier
) {
    val phase = snapshot?.phase ?: RunPhase.Idle
    val seconds = snapshot?.secondsUntilNext ?: 0

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                status == CampaignStatus.Paused || phase == RunPhase.Paused -> {
                    val (headline, whatToDo) = explainPause(campaign.pauseReason)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = ScopeAmber)
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleMedium,
                            color = ScopeAmber
                        )
                    }
                    Text(whatToDo, style = MaterialTheme.typography.bodyMedium)
                }

                status.isFinished || phase == RunPhase.Finished -> {
                    Text(
                        text = if (status == CampaignStatus.Stopped) {
                            "Campaign stopped"
                        } else {
                            "Campaign finished"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${progress.sent} sent, ${progress.failed} failed and " +
                            "${progress.skipped} skipped, out of ${progress.total}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (progress.pending > 0) {
                        Text(
                            text = "${progress.pending} were never messaged. Stopping is final, " +
                                "so this campaign can't pick them up — put them in a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                phase == RunPhase.Sending -> {
                    Text(
                        text = "Messaging ${snapshot?.currentRecipient ?: "the next person"}…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Keep the phone unlocked and on this screen. Typing is paced to " +
                            "the length of the message, so a long one takes a while to appear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                phase == RunPhase.Waiting -> {
                    Text(
                        text = "Next message in ${seconds}s",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "This gap is randomised every time on purpose — a steady interval " +
                            "is itself a fingerprint, and it is the thing that gets numbers banned.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                phase == RunPhase.LongPause -> {
                    Text(
                        text = "Taking a break — back in ${seconds}s",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Every dozen or so messages the app stops for a few minutes, " +
                            "because a person sending by hand would. The break length is " +
                            "randomised too, for the same reason the gaps are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                status == CampaignStatus.Running -> {
                    Text("Starting…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Waiting for the send loop to report in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                status == CampaignStatus.Scheduled -> {
                    Text("Scheduled", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = scheduledLine(campaign.scheduledAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text("Not started", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${progress.pending} people are queued and nothing has gone out yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Shown alongside whatever the phase is: a failure the loop
            // recovered from still matters, and it is the first thing to check
            // when the run looks slower than it should.
            val lastError = snapshot?.lastError?.takeIf { it.isNotBlank() }
            if (lastError != null) {
                Text(
                    text = "Last problem: $lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Active hours ${hour(campaign.activeHoursStart)}–" +
                    "${hour(campaign.activeHoursEnd)}  ·  ${campaign.pacingProfile} pacing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A circuit-breaker pause in the client's words: what tripped, and what to do
 * next. Architecture doc section 6 layer 4 — "pausing is always safe, never
 * keep trying" — only holds if the person reading this knows which pauses are
 * routine and which one means put the phone down.
 */
private fun PauseReason.explain(): Pair<String, String> = when (this) {
    PauseReason.ConsecutiveFailures ->
        "Three messages in a row didn't go" to
            "Something is wrong with the phone, not the list. Open WhatsApp and send one " +
            "message by hand. If that works, resume. If it doesn't, fix that first — " +
            "resuming into a broken state just piles up more failures."

    PauseReason.RestrictionDialogShown ->
        "WhatsApp showed a warning about this account" to
            "Stop for the day. Don't resume. Retrying straight after a warning is what turns " +
            "a warning into a ban. Come back tomorrow on Safe pacing, or switch to the " +
            "second number."

    PauseReason.ColdBatchNoReplies ->
        "A whole batch went out and nobody replied" to
            "To WhatsApp that reads as a cold list, which is the riskiest thing you can send. " +
            "Check these are people who know you, and send to saved contacts and past " +
            "repliers first before resuming."

    PauseReason.DailyCapReached ->
        "Today's warm-up cap is used up" to
            "Nothing more goes out today. Resume tomorrow — the cap rises on its own as the " +
            "number warms up. Going over it by hand is the single fastest way to lose a number."

    PauseReason.OutsideActiveHours ->
        "It's outside your sending hours" to
            "Nobody hand-types blasts at 3am, so the app won't either. It will resume inside " +
            "your active hours; widen them in Settings if these are genuinely your hours."
}

/**
 * [CampaignEntity.pauseReason] is a plain column, not an enum, because a pause
 * can also come from the user rather than a circuit breaker — a different
 * message, and not one of the five reasons in section 6 layer 4.
 *
 * Anything unrecognised is shown verbatim rather than swallowed. A pause we
 * can't explain is still a pause the client needs to see the cause of.
 */
private fun explainPause(reason: String?): Pair<String, String> {
    val known = PauseReason.entries.firstOrNull { it.name == reason }
    if (known != null) return known.explain()

    val userPause = "Paused" to
        "Nothing goes out until you press Resume. The queue is saved — resuming picks up at " +
        "exactly the person it stopped on."

    // The service tags a user-pressed pause with this marker; it is a status
    // flag, not a sentence, so it must never reach the screen as one.
    if (reason.isNullOrBlank() || reason == PAUSED_BY_USER) return userPause

    return "Paused" to reason
}

/** Mirrors `CampaignJobService`'s private marker for a user-pressed pause. */
private const val PAUSED_BY_USER = "PausedByUser"

// ---- recipients ----------------------------------------------------------

@Composable
private fun RecipientListRow(row: RecipientRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(row.phoneE164)
                    row.error?.let { append("  ·  $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (row.status == MessageStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            text = if (row.status == MessageStatus.Pending) "#${row.queuePosition}" else row.status.name,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor(row.status)
        )
    }
    HorizontalDivider()
}

@Composable
private fun statusColor(status: MessageStatus): Color = when (status) {
    MessageStatus.Sent -> MaterialTheme.colorScheme.primary
    MessageStatus.Failed -> MaterialTheme.colorScheme.error
    // Skipped is the anti-ban system working, not a problem — it must not read
    // as an error next to the failures.
    MessageStatus.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
    MessageStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---- controls ------------------------------------------------------------

@Composable
private fun ControlBar(
    status: CampaignStatus,
    startedBefore: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val canPause = status == CampaignStatus.Running
    val canResume = status == CampaignStatus.Paused ||
        status == CampaignStatus.Draft ||
        status == CampaignStatus.Scheduled
    val canStop = !status.isFinished

    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPause,
                enabled = canPause,
                modifier = Modifier.weight(1f)
            ) { Text("Pause") }

            Button(
                onClick = onResume,
                enabled = canResume,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(
                    text = if (startedBefore) "Resume" else "Start",
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            OutlinedButton(
                onClick = onStop,
                enabled = canStop,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = if (canStop) ScopeRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Stop",
                    modifier = Modifier.padding(start = 4.dp),
                    color = if (canStop) ScopeRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- formatting ----------------------------------------------------------

private val SCHEDULE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM 'at' HH:mm")

private fun scheduledLine(scheduledAt: Long?): String {
    if (scheduledAt == null) return "Waiting to be started."
    val at = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault())
    return "Starts ${SCHEDULE_FORMAT.format(at)}. Keep the phone on and unlocked."
}

private fun hour(value: Int): String = "${value.coerceIn(0, 23)}:00"
