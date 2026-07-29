package com.tricreta.scopewa.jobrunner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the group-add loop is doing right now. */
enum class GroupAddPhase {
    /** Nothing running. */
    Idle,

    /** Bringing WhatsApp to the group's info screen. */
    OpeningGroup,

    /** Counting down the randomised 60–150s gap before the next add. */
    Waiting,

    /** Driving WhatsApp to add one person. */
    Adding,

    /** The 8–15 minute break between batches of 3. */
    Cooldown,

    /** Stopped by the user or by a stop rule; the queue survives. */
    Paused,

    /** Queue drained, or the daily cap of 20 was reached. */
    Finished
}

data class GroupAddSnapshot(
    val jobId: Long,
    val phase: GroupAddPhase,
    /** Display name or number of the person being added; null when idle. */
    val currentPerson: String? = null,
    /** Counts down while waiting or cooling down; 0 otherwise. */
    val secondsUntilNext: Int = 0,
    /** 1-based, for "batch 2 of 5". */
    val batchNumber: Int = 0,
    val batchCount: Int = 0,
    val addedToday: Int = 0,
    val lastError: String? = null
)

/**
 * The live view of the running group-add job, published by
 * [GroupAddJobService] and consumed by the Group Add running screen.
 *
 * Same shape and same reasoning as [CampaignRunState]: a process-wide singleton
 * because the job has to keep running whether or not a screen is watching, and
 * the durable state lives in Room. Losing this on process death is expected.
 *
 * Kept as its own object rather than shared with [CampaignRunState] because a
 * group add and a campaign can be started independently, and one clobbering the
 * other's countdown would misreport both.
 */
object GroupAddRunState {

    private val _snapshot = MutableStateFlow<GroupAddSnapshot?>(null)

    /** Null when nothing is running. */
    val snapshot: StateFlow<GroupAddSnapshot?> = _snapshot.asStateFlow()

    internal fun publish(value: GroupAddSnapshot) {
        _snapshot.value = value
    }

    internal fun update(transform: (GroupAddSnapshot) -> GroupAddSnapshot) {
        _snapshot.value = _snapshot.value?.let(transform)
    }

    internal fun clear() {
        _snapshot.value = null
    }
}
