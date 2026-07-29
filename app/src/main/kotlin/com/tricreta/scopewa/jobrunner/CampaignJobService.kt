package com.tricreta.scopewa.jobrunner

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tricreta.scopewa.MainActivity
import com.tricreta.scopewa.R
import com.tricreta.scopewa.ScopeWaApplication
import com.tricreta.scopewa.accessibility.NotificationPermission
import com.tricreta.scopewa.accessibility.SendOutcome
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.WaSender
import com.tricreta.scopewa.brain.campaign.CampaignEngine
import com.tricreta.scopewa.brain.campaign.CampaignStep
import com.tricreta.scopewa.brain.campaign.EngineState
import com.tricreta.scopewa.brain.campaign.PacingProfileCatalog
import com.tricreta.scopewa.brain.pacing.PacingPlanner
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.db.entity.CampaignStatus
import com.tricreta.scopewa.data.repository.campaign.CampaignRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The "JOB RUNNER" layer from architecture doc section 5.2: take the next step,
 * wait, survive, resume where it stopped.
 *
 * A foreground service is the right shape, and the architecture doc says so
 * explicitly (section 5.2, "Why a foreground service here, when v1 forbids
 * them"): this is long-running, user-started, visible work, and Android kills
 * anything else mid-campaign.
 *
 * ## What this loop is actually for
 *
 * Iterating a list and sending would be three lines. Everything else here *is*
 * the product the client is paying for:
 *
 * - **Randomised gaps** between messages and a long break every N, from
 *   [PacingPlanner] — section 6 layer 2.
 * - **Circuit breakers evaluated before every single send**, not on a timer, so
 *   a restriction dialog or the daily cap stops the run immediately — section 6
 *   layer 4. Pausing is always safe; "keep trying" is what gets numbers banned.
 * - **A suppression re-check per recipient**, because someone can reply STOP
 *   while the campaign is mid-flight and must not be messaged after that.
 * - **A wake lock**, because a phone that sleeps stalls the loop silently —
 *   section 8, "Phone locks mid-campaign".
 *
 * Progress is written to Room as it happens, so being killed costs at most the
 * single message in flight.
 */
class CampaignJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val repository: CampaignRepository by lazy { CampaignRepository.create(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                val id = CampaignRunState.snapshot.value?.campaignId
                stopRun()
                if (id != null) scope.launch { repository.pause(id, PAUSED_BY_USER) }
                stopSelfSafely()
            }

            ACTION_STOP -> {
                stopRun()
                stopSelfSafely()
            }

            else -> {
                val campaignId = intent?.getLongExtra(EXTRA_CAMPAIGN_ID, NO_CAMPAIGN) ?: NO_CAMPAIGN
                if (campaignId == NO_CAMPAIGN) {
                    stopSelfSafely()
                } else {
                    startForegroundSafely(buildNotification("Starting…", campaignId))
                    startRun(campaignId)
                    // If Android kills us mid-campaign we want to come back and
                    // finish the queue, not forget it.
                    return START_REDELIVER_INTENT
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRun()
        scope.cancel()
        super.onDestroy()
    }

    // ---- the loop ----------------------------------------------------------

    private fun startRun(campaignId: Long) {
        runJob?.cancel()
        acquireWakeLock()
        runJob = scope.launch { runCampaign(campaignId) }
    }

    private fun stopRun() {
        runJob?.cancel()
        runJob = null
        releaseWakeLock()
        CampaignRunState.clear()
    }

    private suspend fun runCampaign(campaignId: Long) {
        val campaign = repository.campaign(campaignId)
        if (campaign == null) {
            stopSelfSafely()
            return
        }

        CampaignRunState.publish(RunSnapshot(campaignId, RunPhase.Waiting))

        if (!awaitScheduledStart(campaign)) return
        repository.start(campaignId)

        val profile = PacingProfileCatalog.byName(campaign.pacingProfile)
        val planner = PacingPlanner(profile)
        val engine = CampaignEngine(planner)
        val target = WaPackage.entries.firstOrNull { it.name == campaign.waPackage } ?: WaPackage.Consumer

        var consecutiveFailures = 0
        var sentSinceLastLongPause = 0
        var restrictionSeen = false
        var running = true

        // A "batch" for the cold-batch breaker is the same run of messages the
        // pacing profile takes a long break after — so `sentSinceLastLongPause`
        // is also the batch counter, and the long pause is the batch boundary.
        // Replies are counted by time rather than by row so the window matches
        // exactly the messages in this batch.
        var batchStartedAt = System.currentTimeMillis()

        while (running && scope.isActive) {
            // Re-read every iteration: the user may have paused or stopped from
            // the Running screen while we were asleep between messages.
            val current = repository.campaign(campaignId)
            if (current == null || CampaignStatus.fromName(current.status) != CampaignStatus.Running) break

            // Re-read rather than cached: the user can revoke notification
            // access mid-campaign, and the breaker must go back to being
            // disabled the moment they do rather than pausing the run.
            val replyTracking = NotificationPermission.isGranted(applicationContext)

            val state = EngineState(
                pendingCount = repository.pendingCount(campaignId),
                sentSinceLastLongPause = sentSinceLastLongPause,
                consecutiveFailures = consecutiveFailures,
                restrictionDialogSeen = restrictionSeen,
                sentToday = repository.sentToday(),
                dailyCap = repository.dailyCap(),
                currentHour = currentHour(),
                activeHoursStart = current.activeHoursStart,
                activeHoursEnd = current.activeHoursEnd,
                repliesInCurrentBatch =
                    if (replyTracking) repository.repliesSince(campaignId, batchStartedAt) else 0,
                sentInCurrentBatch = sentSinceLastLongPause,
                batchSizeForReplyCheck = profile.pauseEveryMessages,
                replyTrackingAvailable = replyTracking
            )

            when (val step = engine.nextStep(state)) {
                is CampaignStep.Finished -> {
                    repository.complete(campaignId)
                    CampaignRunState.publish(RunSnapshot(campaignId, RunPhase.Finished))
                    notify(buildNotification("Campaign finished", campaignId))
                    running = false
                }

                is CampaignStep.Pause -> {
                    repository.pause(campaignId, step.reason.name)
                    CampaignRunState.publish(
                        RunSnapshot(campaignId, RunPhase.Paused, lastError = step.reason.name)
                    )
                    notify(buildNotification("Paused — ${step.reason.name}", campaignId))
                    running = false
                }

                is CampaignStep.LongPause -> {
                    // Reaching a long pause means the batch completed *without*
                    // the cold-batch breaker firing — safety is evaluated first
                    // — so this is exactly where the next batch begins.
                    sentSinceLastLongPause = 0
                    batchStartedAt = System.currentTimeMillis()
                    countDown(campaignId, RunPhase.LongPause, step.seconds, null)
                }

                is CampaignStep.Send -> {
                    val message = repository.nextPending(campaignId)
                    if (message == null) {
                        // Queue drained between the count and the read.
                        continue
                    }
                    val label = message.displayName.ifBlank { message.phoneE164 }
                    countDown(campaignId, RunPhase.Waiting, step.delaySeconds, label)
                    if (!scope.isActive) break

                    when (val outcome = sendOne(campaignId, target, message)) {
                        is SendOutcome.Sent -> {
                            consecutiveFailures = 0
                            sentSinceLastLongPause++
                        }

                        // The one outcome that must stop everything rather than
                        // count as one more failure among several.
                        is SendOutcome.Restricted -> restrictionSeen = true

                        else -> if (outcome.isFailure) consecutiveFailures++
                    }
                }
            }
        }

        releaseWakeLock()
        stopSelfSafely()
    }

    /** Honours [CampaignEntity.scheduledAt]. Returns false if cancelled first. */
    private suspend fun awaitScheduledStart(campaign: CampaignEntity): Boolean {
        val startAt = campaign.scheduledAt ?: return true
        while (scope.isActive) {
            val remaining = startAt - System.currentTimeMillis()
            if (remaining <= 0) return true
            CampaignRunState.publish(
                RunSnapshot(
                    campaignId = campaign.id,
                    phase = RunPhase.Waiting,
                    secondsUntilNext = (remaining / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            )
            notify(buildNotification("Scheduled — waiting to start", campaign.id))
            delay(minOf(remaining, TICK_MS))
        }
        return false
    }

    private suspend fun sendOne(
        campaignId: Long,
        target: WaPackage,
        message: CampaignMessageEntity
    ): SendOutcome {
        // Someone can reply STOP while the campaign is mid-flight; checking
        // once at queue-build time is not enough.
        if (message.phoneE164 in repository.suppressedNumbers()) {
            val reason = "Opted out while the campaign was running"
            repository.recordSkipped(message, reason)
            return SendOutcome.Skipped(reason)
        }

        val label = message.displayName.ifBlank { message.phoneE164 }
        CampaignRunState.publish(RunSnapshot(campaignId, RunPhase.Sending, currentRecipient = label))
        notify(buildNotification("Messaging $label", campaignId))

        val outcome = runCatching {
            WaSender.send(
                context = applicationContext,
                target = target,
                e164 = message.phoneE164,
                text = message.renderedText
            )
        }.getOrElse { error ->
            Log.w(TAG, "send threw", error)
            SendOutcome.NotDelivered(error.message ?: "Unexpected error while sending")
        }

        if (outcome.isSent) {
            repository.recordSent(message)
        } else {
            repository.recordFailed(message, outcome.describe())
        }
        return outcome
    }

    /**
     * A visible, ticking countdown. The randomised gap *is* the anti-ban
     * feature, so the user should be able to watch it rather than wonder
     * whether the app has hung.
     */
    private suspend fun countDown(campaignId: Long, phase: RunPhase, seconds: Int, recipient: String?) {
        var remaining = seconds
        while (remaining > 0 && scope.isActive) {
            CampaignRunState.publish(
                RunSnapshot(campaignId, phase, currentRecipient = recipient, secondsUntilNext = remaining)
            )
            val label = if (phase == RunPhase.LongPause) "Break — ${remaining}s" else "Next in ${remaining}s"
            notify(buildNotification(label, campaignId))
            delay(TICK_MS)
            remaining--
        }
    }

    private fun currentHour(): Int =
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).hour

    // ---- foreground plumbing ----------------------------------------------

    private fun buildNotification(text: String, campaignId: Long?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ScopeWaApplication.CAMPAIGN_CHANNEL_ID)
            .setContentTitle(if (campaignId == null) "Scope WA" else "Campaign running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            // POST_NOTIFICATIONS can be denied on Android 13+. The campaign
            // still runs; the user just doesn't get the ticker.
            Log.d(TAG, "notify skipped: ${it.message}")
        }
    }

    private fun stopSelfSafely() {
        // minSdk is 26, so the constant-based overload is always available.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Campaigns run for hours with long idle gaps, and a sleeping phone stalls
     * the loop silently. Section 8 calls for this alongside the "keep the phone
     * plugged in and on this screen" instruction.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "CampaignJobService"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 1_000L
        private const val NO_CAMPAIGN = -1L
        private const val PAUSED_BY_USER = "PausedByUser"
        private const val WAKE_LOCK_TAG = "ScopeWA::campaign"

        /** Long enough for a full campaign, short enough that a bug can't pin
         *  the CPU awake forever. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        private const val ACTION_START = "com.tricreta.scopewa.action.START_CAMPAIGN"
        private const val ACTION_PAUSE = "com.tricreta.scopewa.action.PAUSE_CAMPAIGN"
        private const val ACTION_STOP = "com.tricreta.scopewa.action.STOP_CAMPAIGN"
        private const val EXTRA_CAMPAIGN_ID = "campaign_id"

        fun start(context: Context, campaignId: Long) {
            val intent = Intent(context, CampaignJobService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CAMPAIGN_ID, campaignId)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start campaign service", it) }
        }

        fun pause(context: Context) = signal(context, ACTION_PAUSE)

        fun stop(context: Context) = signal(context, ACTION_STOP)

        private fun signal(context: Context, action: String) {
            val intent = Intent(context, CampaignJobService::class.java).apply { this.action = action }
            runCatching { context.startService(intent) }
        }
    }
}
