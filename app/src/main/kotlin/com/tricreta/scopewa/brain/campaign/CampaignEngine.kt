package com.tricreta.scopewa.brain.campaign

import com.tricreta.scopewa.brain.pacing.PacingPlanner
import com.tricreta.scopewa.brain.safety.CampaignSafetyState
import com.tricreta.scopewa.brain.safety.CircuitBreaker
import com.tricreta.scopewa.brain.safety.PauseReason

/**
 * Everything the engine needs to decide what happens next. Deliberately plain
 * data with no clock and no database, so every branch is testable in CI.
 */
data class EngineState(
    val pendingCount: Int,
    val sentSinceLastLongPause: Int,
    val consecutiveFailures: Int,
    val restrictionDialogSeen: Boolean,
    val sentToday: Int,
    val dailyCap: Int,
    val currentHour: Int,
    val activeHoursStart: Int,
    val activeHoursEnd: Int,
    val repliesInCurrentBatch: Int = 0,
    val sentInCurrentBatch: Int = 0,
    val batchSizeForReplyCheck: Int = 0
)

/** The single next thing the foreground service should do. */
sealed interface CampaignStep {

    /** Queue is empty — every recipient has a final outcome. */
    data object Finished : CampaignStep

    /** A circuit breaker fired. The job survives and can resume; never retry. */
    data class Pause(val reason: PauseReason) : CampaignStep

    /** Humans take breaks — section 6 layer 2. */
    data class LongPause(val seconds: Int) : CampaignStep

    /** Wait [delaySeconds], then send the next queued message. */
    data class Send(val delaySeconds: Int) : CampaignStep
}

/**
 * Decides what a running campaign does next, combining the Phase 0 brain pieces
 * that were built for exactly this: [PacingPlanner] for randomised human-like
 * delays, [CircuitBreaker] for the five auto-pause conditions in section 6
 * layer 4.
 *
 * **Order matters and is not arbitrary.** Safety is evaluated before pacing, so
 * a campaign that has hit its daily cap or seen a WhatsApp warning pauses
 * *now* rather than after one more polite delay. Pausing is always safe; the
 * one thing the design forbids is "keep trying".
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
class CampaignEngine(private val planner: PacingPlanner) {

    fun nextStep(state: EngineState): CampaignStep {
        if (state.pendingCount <= 0) return CampaignStep.Finished

        checkSafety(state)?.let { return CampaignStep.Pause(it) }

        if (planner.isLongPauseDue(state.sentSinceLastLongPause)) {
            return CampaignStep.LongPause(planner.nextLongPauseSeconds())
        }

        return CampaignStep.Send(planner.nextDelaySeconds())
    }

    /** Exposed so the composer can warn "this won't start — you're outside
     *  active hours" before the user presses Start. */
    fun checkSafety(state: EngineState): PauseReason? =
        CircuitBreaker.checkPauseReason(
            CampaignSafetyState(
                consecutiveFailures = state.consecutiveFailures,
                whatsAppShowedRestrictionDialog = state.restrictionDialogSeen,
                sentInCurrentBatch = if (replyCheckEnabled(state)) state.sentInCurrentBatch else 0,
                repliesInCurrentBatch = state.repliesInCurrentBatch,
                // CircuitBreaker's cold-batch rule is `sentInCurrentBatch >=
                // batchSizeForReplyCheck && repliesInCurrentBatch == 0`, which
                // is true for all-zeros — so a campaign that hasn't opted into
                // reply tracking would pause with ColdBatchNoReplies before its
                // very first message. Push the threshold out of reach instead
                // of weakening a Phase 0 rule that other phases rely on.
                batchSizeForReplyCheck =
                    if (replyCheckEnabled(state)) state.batchSizeForReplyCheck else Int.MAX_VALUE,
                sentToday = state.sentToday,
                dailyCap = state.dailyCap,
                currentHour = state.currentHour,
                activeHoursStart = state.activeHoursStart,
                activeHoursEnd = state.activeHoursEnd
            )
        )

    /**
     * Reply tracking only means something once we're actually counting a batch.
     * Until then "zero replies" is not evidence of a cold list, it's evidence
     * that nothing has been sent.
     */
    private fun replyCheckEnabled(state: EngineState): Boolean =
        state.batchSizeForReplyCheck > 0 && state.sentInCurrentBatch > 0
}

/**
 * How long a person would plausibly spend typing [text].
 *
 * Section 6 layer 2: "a 300-character message shouldn't appear in 200ms". The
 * deep link prefills the compose box instantly, so without this the send is
 * indistinguishable from a script no matter how long the gap before it was.
 *
 * Clamped at both ends: a two-word message still takes a moment, and a very
 * long one doesn't stall the queue for a minute.
 */
object TypingDelay {

    const val MILLIS_PER_CHARACTER = 55L
    const val MINIMUM_MILLIS = 900L
    const val MAXIMUM_MILLIS = 12_000L

    fun forText(text: String): Long =
        (text.length * MILLIS_PER_CHARACTER).coerceIn(MINIMUM_MILLIS, MAXIMUM_MILLIS)
}
