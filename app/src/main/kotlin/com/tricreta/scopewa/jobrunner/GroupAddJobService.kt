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
import com.tricreta.scopewa.accessibility.WaGroupAdder
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.brain.groupadd.GroupAddGateState
import com.tricreta.scopewa.brain.groupadd.GroupAddOutcome
import com.tricreta.scopewa.brain.groupadd.GroupAddPlanner
import com.tricreta.scopewa.brain.groupadd.GroupAddStep
import com.tricreta.scopewa.brain.groupadd.GroupAddStopReason
import com.tricreta.scopewa.data.db.entity.GroupAddStatus
import com.tricreta.scopewa.data.repository.groupadd.GroupAddRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The group-add half of the "JOB RUNNER" layer — architecture doc section 5.2,
 * running the strictest rules in the product (section 6, **layer 5**).
 *
 * Deliberately a separate service from [CampaignJobService] rather than a mode
 * of it. They share a shape but almost no rules: different pacing, a different
 * daily cap, a two-deep failure breaker instead of three, and a terminal
 * "needs invite" bucket that sending has no equivalent of. Folding them
 * together would mean a flag on every one of those, and the flag that gets set
 * wrong is the one that adds 20 cold numbers to a group.
 *
 * ## What this loop is for
 *
 * Nothing here is about adding people quickly. Every part of it exists to add
 * them *slowly and in the right order*:
 *
 * - **A plan built up front** by [GroupAddPlanner] — batches of 3, randomised
 *   60–150s gaps, randomised 8–15 minute cooldowns.
 * - **The daily cap of 20 read across every job**, not this one, so a second
 *   job started in the same day gets no fresh allowance.
 * - **[GroupAddPlanner.stopReasonFor] asked before every single add**, so a
 *   restriction dialog or the second consecutive failure stops the run
 *   immediately rather than at the next batch boundary.
 * - **A wake lock**, because a batched run with 15-minute breaks spends most of
 *   its life idle and a sleeping phone stalls it silently (section 8).
 *
 * Progress is written to Room after every person, so being killed costs at most
 * the one add in flight — and a `NeedsInviteLink` result is durable the moment
 * it happens.
 */
class GroupAddJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val repository: GroupAddRepository by lazy {
        GroupAddRepository.create(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                val id = GroupAddRunState.snapshot.value?.jobId
                stopRun()
                if (id != null) scope.launch { repository.pause(id, PAUSED_BY_USER) }
                stopSelfSafely()
            }

            ACTION_STOP -> {
                val id = GroupAddRunState.snapshot.value?.jobId
                stopRun()
                if (id != null) {
                    scope.launch { repository.stop(id, GroupAddStopReason.StoppedByUser.name) }
                }
                stopSelfSafely()
            }

            else -> {
                val jobId = intent?.getLongExtra(EXTRA_JOB_ID, NO_JOB) ?: NO_JOB
                if (jobId == NO_JOB) {
                    stopSelfSafely()
                } else {
                    startForegroundSafely(buildNotification("Starting…"))
                    startRun(jobId)
                    // A group add takes hours of mostly waiting; if Android
                    // kills us we want to come back and finish the queue.
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

    private fun startRun(jobId: Long) {
        runJob?.cancel()
        acquireWakeLock()
        runJob = scope.launch { runGroupAdd(jobId) }
    }

    private fun stopRun() {
        runJob?.cancel()
        runJob = null
        releaseWakeLock()
        GroupAddRunState.clear()
    }

    private suspend fun runGroupAdd(jobId: Long) {
        val job = repository.job(jobId)
        if (job == null) {
            stopSelfSafely()
            return
        }

        repository.start(jobId)

        val target = WaPackage.fromPackageName(job.waPackage) ?: WaPackage.Consumer
        val addedTodayAtStart = repository.addedToday()

        // Plan the whole run before touching WhatsApp, so the batching and the
        // cap are decided by pure, tested logic rather than by this loop.
        val plan = GroupAddPlanner().plan(
            eligible = repository.queuedCandidates(job),
            addedToday = addedTodayAtStart
        )
        repository.setQueue(
            id = jobId,
            ordered = plan.adds.map { it.candidate.phoneE164 } +
                plan.deferredToTomorrow.map { it.phoneE164 }
        )

        if (plan.isEmpty) {
            repository.finish(jobId, plan.endsWith.name)
            publish(jobId, GroupAddPhase.Finished, addedToday = addedTodayAtStart)
            notify(buildNotification(plan.endsWith.label))
            releaseWakeLock()
            stopSelfSafely()
            return
        }

        var consecutiveFailures = 0
        var restrictionSeen = false
        var addedToday = addedTodayAtStart
        var openBatch = -1
        var participantCount: Int? = null
        var endedWith: GroupAddStopReason = plan.endsWith
        var running = true

        for (step in plan.steps) {
            if (!running || !scope.isActive) break

            // Re-read every step: the user may have paused or stopped from the
            // running screen while we were asleep in a 15-minute cooldown.
            val current = repository.job(jobId)
            if (current == null || GroupAddStatus.fromName(current.status) != GroupAddStatus.Running) {
                running = false
                break
            }

            when (step) {
                is GroupAddStep.Cooldown -> {
                    // The group info screen will have gone stale by the time a
                    // cooldown ends, so the next batch reopens it.
                    openBatch = -1
                    countDown(
                        jobId = jobId,
                        phase = GroupAddPhase.Cooldown,
                        seconds = step.seconds,
                        person = null,
                        batchNumber = step.afterBatchIndex + 1,
                        batchCount = plan.batchCount,
                        addedToday = addedToday
                    )
                }

                is GroupAddStep.Add -> {
                    val gate = GroupAddPlanner.stopReasonFor(
                        GroupAddGateState(
                            consecutiveFailures = consecutiveFailures,
                            restrictionDialogSeen = restrictionSeen,
                            addedToday = addedToday,
                            // Only zero once this loop has run out of steps,
                            // which the for-loop already handles; anything
                            // positive keeps the QueueDrained rule quiet.
                            remainingInQueue = 1
                        )
                    )
                    if (gate != null) {
                        endedWith = gate
                        running = false
                        break
                    }

                    if (step.batchIndex != openBatch) {
                        publish(
                            jobId = jobId,
                            phase = GroupAddPhase.OpeningGroup,
                            batchNumber = step.batchIndex + 1,
                            batchCount = plan.batchCount,
                            addedToday = addedToday
                        )
                        notify(buildNotification("Opening ${job.targetGroup}"))

                        when (val opened = WaGroupAdder.openGroupInfo(
                            context = applicationContext,
                            target = target,
                            groupName = job.targetGroup
                        )) {
                            is WaGroupAdder.GroupOpen.Ready -> {
                                openBatch = step.batchIndex
                                participantCount = opened.participantCount
                            }

                            is WaGroupAdder.GroupOpen.Failed -> {
                                // Not being able to open the group is not a
                                // per-person failure — it will fail identically
                                // for everyone, so stopping now is honest.
                                repository.record(jobId, step.candidate.phoneE164, opened.outcome)
                                if (opened.outcome.stopsEverything) restrictionSeen = true
                                endedWith = if (restrictionSeen) {
                                    GroupAddStopReason.RestrictionDialogShown
                                } else {
                                    GroupAddStopReason.ConsecutiveFailures
                                }
                                running = false
                                break
                            }
                        }
                    }

                    val label = step.candidate.label
                    countDown(
                        jobId = jobId,
                        phase = GroupAddPhase.Waiting,
                        seconds = step.delaySecondsBefore,
                        person = label,
                        batchNumber = step.batchIndex + 1,
                        batchCount = plan.batchCount,
                        addedToday = addedToday
                    )
                    if (!scope.isActive) break

                    publish(
                        jobId = jobId,
                        phase = GroupAddPhase.Adding,
                        person = label,
                        batchNumber = step.batchIndex + 1,
                        batchCount = plan.batchCount,
                        addedToday = addedToday
                    )
                    notify(buildNotification("Adding $label to ${job.targetGroup}"))

                    val outcome = runCatching {
                        WaGroupAdder.addOne(
                            target = target,
                            candidate = step.candidate,
                            countBefore = participantCount
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "add threw", error)
                        GroupAddOutcome.NotConfirmed(error.message ?: "Unexpected error while adding")
                    }

                    repository.record(jobId, step.candidate.phoneE164, outcome)

                    when {
                        outcome.isAdded -> {
                            consecutiveFailures = 0
                            addedToday++
                            participantCount = participantCount?.plus(1)
                        }

                        // Stops the whole job, rather than counting as one more
                        // failure among several — section 6 layer 4.
                        outcome.stopsEverything -> restrictionSeen = true

                        // A privacy block and a skip are both explicitly *not*
                        // failures. Neither is evidence the automation broke,
                        // and the breaker is only two deep.
                        outcome.isFailure -> {
                            consecutiveFailures++
                            participantCount = WaGroupAdder.participantCount() ?: participantCount
                        }

                        else -> consecutiveFailures = 0
                    }
                }
            }
        }

        // One last gate read, so a run that ended on its final step still
        // reports the real reason rather than "queue drained".
        val finalReason = GroupAddPlanner.stopReasonFor(
            GroupAddGateState(
                consecutiveFailures = consecutiveFailures,
                restrictionDialogSeen = restrictionSeen,
                addedToday = addedToday,
                remainingInQueue = repository.job(jobId)?.pending?.size ?: 0
            )
        ) ?: endedWith

        if (scope.isActive) {
            if (finalReason == GroupAddStopReason.RestrictionDialogShown ||
                finalReason == GroupAddStopReason.ConsecutiveFailures
            ) {
                // Resumable: the queue survives and the user decides whether to
                // carry on. Pausing is always safe; "keep trying" is not.
                repository.pause(jobId, finalReason.name)
                publish(jobId, GroupAddPhase.Paused, addedToday = addedToday, lastError = finalReason.label)
            } else {
                repository.finish(jobId, finalReason.name)
                publish(jobId, GroupAddPhase.Finished, addedToday = addedToday)
            }
            notify(buildNotification(finalReason.label))
        }

        releaseWakeLock()
        stopSelfSafely()
    }

    // ---- progress ----------------------------------------------------------

    /**
     * A visible, ticking countdown. The 60–150s gap and the 8–15 minute break
     * *are* the anti-ban feature, so the user should be able to watch them
     * rather than wonder whether the app has hung.
     */
    private suspend fun countDown(
        jobId: Long,
        phase: GroupAddPhase,
        seconds: Int,
        person: String?,
        batchNumber: Int,
        batchCount: Int,
        addedToday: Int
    ) {
        var remaining = seconds
        while (remaining > 0 && scope.isActive) {
            publish(
                jobId = jobId,
                phase = phase,
                person = person,
                seconds = remaining,
                batchNumber = batchNumber,
                batchCount = batchCount,
                addedToday = addedToday
            )
            val text = if (phase == GroupAddPhase.Cooldown) {
                "Break between batches — ${remaining}s"
            } else {
                "Next add in ${remaining}s"
            }
            notify(buildNotification(text))
            delay(TICK_MS)
            remaining--
        }
    }

    private fun publish(
        jobId: Long,
        phase: GroupAddPhase,
        person: String? = null,
        seconds: Int = 0,
        batchNumber: Int = 0,
        batchCount: Int = 0,
        addedToday: Int = 0,
        lastError: String? = null
    ) {
        GroupAddRunState.publish(
            GroupAddSnapshot(
                jobId = jobId,
                phase = phase,
                currentPerson = person,
                secondsUntilNext = seconds,
                batchNumber = batchNumber,
                batchCount = batchCount,
                addedToday = addedToday,
                lastError = lastError
            )
        )
    }

    // ---- foreground plumbing ----------------------------------------------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ScopeWaApplication.CAMPAIGN_CHANNEL_ID)
            .setContentTitle("Adding people to a group")
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
            // POST_NOTIFICATIONS can be denied on Android 13+. The job still
            // runs; the user just doesn't get the ticker.
            Log.d(TAG, "notify skipped: ${it.message}")
        }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

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
        private const val TAG = "GroupAddJobService"

        /** Distinct from [CampaignJobService]'s, so the two tickers don't overwrite each other. */
        private const val NOTIFICATION_ID = 1002

        private const val TICK_MS = 1_000L
        private const val NO_JOB = -1L
        private const val PAUSED_BY_USER = "PausedByUser"
        private const val WAKE_LOCK_TAG = "ScopeWA::groupadd"

        /**
         * A full day's allowance of 20 adds at this pacing is a few hours;
         * four is generous headroom without letting a bug pin the CPU awake
         * indefinitely.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L

        private const val ACTION_START = "com.tricreta.scopewa.action.START_GROUP_ADD"
        private const val ACTION_PAUSE = "com.tricreta.scopewa.action.PAUSE_GROUP_ADD"
        private const val ACTION_STOP = "com.tricreta.scopewa.action.STOP_GROUP_ADD"
        private const val EXTRA_JOB_ID = "group_add_job_id"

        fun start(context: Context, jobId: Long) {
            val intent = Intent(context, GroupAddJobService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB_ID, jobId)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start group-add service", it) }
        }

        fun pause(context: Context) = signal(context, ACTION_PAUSE)

        fun stop(context: Context) = signal(context, ACTION_STOP)

        private fun signal(context: Context, action: String) {
            val intent = Intent(context, GroupAddJobService::class.java).apply { this.action = action }
            runCatching { context.startService(intent) }
        }
    }
}
