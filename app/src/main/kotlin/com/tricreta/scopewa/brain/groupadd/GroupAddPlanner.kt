package com.tricreta.scopewa.brain.groupadd

/** Why a group-add run stopped, or will stop. */
enum class GroupAddStopReason(val label: String) {
    /** Everyone eligible has been handled. */
    QueueDrained("Everyone on the list has been handled"),

    /** 20 adds have happened today, on this phone number, across every job. */
    DailyCapReached("Today's cap of ${GroupAddPacing.DAILY_CAP} adds is used up"),

    /** Two in a row didn't work — architecture doc section 6 layer 5. */
    ConsecutiveFailures("Two adds in a row failed"),

    /** WhatsApp warned about the account. */
    RestrictionDialogShown("WhatsApp showed a warning about this account"),

    /** The user pressed Stop. */
    StoppedByUser("Stopped by you")
}

/** One instruction in a planned run. */
sealed interface GroupAddStep {

    /**
     * Add one person, after waiting [delaySecondsBefore]. Every add carries a
     * wait, including the first: opening a group and immediately adding someone
     * is the fingerprint the delay exists to avoid.
     */
    data class Add(
        val candidate: AddCandidate,
        val delaySecondsBefore: Int,
        /** 0-based batch this add belongs to. */
        val batchIndex: Int,
        /** 0-based position inside the batch, always < [GroupAddPacing.BATCH_SIZE]. */
        val positionInBatch: Int
    ) : GroupAddStep

    /** The 8–15 minute break owed after a full batch. */
    data class Cooldown(val seconds: Int, val afterBatchIndex: Int) : GroupAddStep
}

/**
 * The whole run, laid out before it starts.
 *
 * Planning up front rather than deciding step by step is the same choice Phase 5
 * made for the send queue (`MEMORY.md`, "the send queue is frozen when a
 * campaign is created"): the confirmation screen can then quote the real number
 * of batches and the real time it will take, instead of an estimate that the
 * randomiser makes a lie.
 */
data class GroupAddPlan(
    val steps: List<GroupAddStep>,
    /** Eligible people the daily cap pushed past today. Not a failure. */
    val deferredToTomorrow: List<AddCandidate>,
    /** Why the plan ends where it does. */
    val endsWith: GroupAddStopReason
) {
    val adds: List<GroupAddStep.Add> get() = steps.filterIsInstance<GroupAddStep.Add>()

    val cooldowns: List<GroupAddStep.Cooldown> get() = steps.filterIsInstance<GroupAddStep.Cooldown>()

    val addCount: Int get() = adds.size

    val batchCount: Int get() = adds.map { it.batchIndex }.distinct().size

    /** Wall-clock seconds the plan will take if nothing fails. */
    val estimatedSeconds: Int
        get() = adds.sumOf { it.delaySecondsBefore } + cooldowns.sumOf { it.seconds }

    val isEmpty: Boolean get() = adds.isEmpty()
}

/** What the job runner knows just before it takes the next step. */
data class GroupAddGateState(
    val consecutiveFailures: Int,
    val restrictionDialogSeen: Boolean,
    /** Successful adds today across **every** job on this phone number. */
    val addedToday: Int,
    val remainingInQueue: Int
)

/**
 * Pure planner for architecture doc section 6 layer 5.
 *
 * It owns two things and nothing else:
 *
 * 1. [plan] — turn a screened candidate list into an ordered, batched,
 *    randomly-paced set of steps that respects the daily cap.
 * 2. [stopReasonFor] — the gate the running job asks before every add, the
 *    group-add equivalent of [com.tricreta.scopewa.brain.safety.CircuitBreaker].
 *    It never decides to keep trying past a limit.
 *
 * The daily cap is applied against a **phone-number-wide** count, not a
 * per-job one. Three group-add jobs in a day share one allowance of 20, exactly
 * as Phase 5 decided for sending (`MEMORY.md`, "the daily cap belongs to the
 * phone number, not the campaign"). A cap you can reset by starting a second
 * job is not a cap.
 */
class GroupAddPlanner(private val pacer: GroupAddPacer = GroupAddPacer()) {

    /**
     * @param eligible candidates that already passed [GroupAddEligibility].
     *   Passing cold numbers here is a programming error, not something this
     *   class silently forgives — it re-screens and drops them.
     * @param addedToday successful adds already made today across all jobs.
     */
    fun plan(eligible: List<AddCandidate>, addedToday: Int): GroupAddPlan {
        // Belt and braces: the eligibility rule is the one thing in this phase
        // that must not be bypassed by a caller that forgot to screen.
        val screened = eligible.filter { GroupAddEligibility.isEligible(it) }

        val allowance = GroupAddPacing.remainingToday(addedToday)
        if (allowance == 0) {
            return GroupAddPlan(
                steps = emptyList(),
                deferredToTomorrow = screened,
                endsWith = GroupAddStopReason.DailyCapReached
            )
        }

        val today = screened.take(allowance)
        val deferred = screened.drop(allowance)

        val steps = mutableListOf<GroupAddStep>()
        val batches = today.chunked(GroupAddPacing.BATCH_SIZE)

        batches.forEachIndexed { batchIndex, batch ->
            batch.forEachIndexed { position, candidate ->
                steps += GroupAddStep.Add(
                    candidate = candidate,
                    delaySecondsBefore = pacer.nextDelaySeconds(),
                    batchIndex = batchIndex,
                    positionInBatch = position
                )
            }
            // No cooldown after the last batch — there is nothing to cool down
            // before, and a trailing 15-minute wait would just look hung.
            if (batchIndex < batches.lastIndex) {
                steps += GroupAddStep.Cooldown(
                    seconds = pacer.nextCooldownSeconds(),
                    afterBatchIndex = batchIndex
                )
            }
        }

        return GroupAddPlan(
            steps = steps,
            deferredToTomorrow = deferred,
            endsWith = if (deferred.isEmpty()) {
                GroupAddStopReason.QueueDrained
            } else {
                GroupAddStopReason.DailyCapReached
            }
        )
    }

    companion object {

        /**
         * The gate asked before every single add, not on a timer. Returns null
         * when it is safe to carry on.
         *
         * Order matters: a restriction dialog outranks everything, because
         * continuing after one is what turns a warning into a ban.
         */
        fun stopReasonFor(state: GroupAddGateState): GroupAddStopReason? = when {
            state.restrictionDialogSeen -> GroupAddStopReason.RestrictionDialogShown
            state.consecutiveFailures >= GroupAddPacing.MAX_CONSECUTIVE_FAILURES ->
                GroupAddStopReason.ConsecutiveFailures
            GroupAddPacing.isDailyCapReached(state.addedToday) -> GroupAddStopReason.DailyCapReached
            state.remainingInQueue <= 0 -> GroupAddStopReason.QueueDrained
            else -> null
        }
    }
}
