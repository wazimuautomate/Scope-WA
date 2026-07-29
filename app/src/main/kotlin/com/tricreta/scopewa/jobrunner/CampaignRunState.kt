package com.tricreta.scopewa.jobrunner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the send loop is doing right now. */
enum class RunPhase {
    /** Nothing running. */
    Idle,

    /** Counting down the randomised gap before the next message. */
    Waiting,

    /** Driving WhatsApp for one recipient. */
    Sending,

    /** The every-N-messages break from architecture doc section 6 layer 2. */
    LongPause,

    /** Stopped by the user or by a circuit breaker; resumable. */
    Paused,

    /** Queue drained. */
    Finished
}

data class RunSnapshot(
    val campaignId: Long,
    val phase: RunPhase,
    /** Display name or number of the person being messaged; null when idle. */
    val currentRecipient: String? = null,
    /** Counts down while waiting or on a long pause; 0 otherwise. */
    val secondsUntilNext: Int = 0,
    val lastError: String? = null
)

/**
 * The live view of the running campaign, published by [CampaignJobService] and
 * consumed by the Running screen.
 *
 * Deliberately a process-wide singleton rather than a bound service: the UI
 * needs to survive the screen being closed and reopened mid-campaign, and the
 * campaign has to keep running whether or not anything is watching. The
 * durable state lives in Room; this is only the second-by-second detail that
 * would be wasteful to write to disk — the countdown, and who is being
 * messaged right now.
 *
 * Losing this on process death is fine and expected. The service rebuilds it
 * from the database when it restarts.
 */
object CampaignRunState {

    private val _snapshot = MutableStateFlow<RunSnapshot?>(null)

    /** Null when nothing is running. */
    val snapshot: StateFlow<RunSnapshot?> = _snapshot.asStateFlow()

    internal fun publish(value: RunSnapshot) {
        _snapshot.value = value
    }

    internal fun update(transform: (RunSnapshot) -> RunSnapshot) {
        _snapshot.value = _snapshot.value?.let(transform)
    }

    internal fun clear() {
        _snapshot.value = null
    }
}
