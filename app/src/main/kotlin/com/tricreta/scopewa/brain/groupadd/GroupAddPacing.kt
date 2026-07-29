package com.tricreta.scopewa.brain.groupadd

import kotlin.random.Random

/**
 * The pacing rules for adding people to a WhatsApp group.
 *
 * These are **not** the same numbers as [com.tricreta.scopewa.brain.pacing.PacingProfile],
 * and there is deliberately no Safe/Normal/Fast choice here. Architecture doc
 * section 6, **layer 5** gives group adding its own, stricter rules because it
 * is the highest-risk action the app performs:
 *
 * | Setting | Value |
 * | --- | --- |
 * | Batch size | 3 |
 * | Delay between adds | 60–150s, random |
 * | Cooldown between batches | 8–15 min, random |
 * | Daily cap | 20 |
 * | Stop after failures | 2 in a row |
 *
 * The doc is explicit that the client's browser extension used a daily cap of
 * 45 and that 45 is **too high for a phone-based add**. Anyone tempted to raise
 * these should read section 6 layer 5 first and then change the doc, not this
 * file: per `CLAUDE.md`, the pacing numbers are the product, not tuning knobs.
 *
 * No Android imports here, on purpose — this is the unit-testable core.
 */
object GroupAddPacing {

    /** People added before the run takes a cooldown. Architecture doc section 6 layer 5. */
    const val BATCH_SIZE = 3

    /** Randomised gap between two adds inside a batch, in seconds. */
    const val MIN_DELAY_SECONDS = 60
    const val MAX_DELAY_SECONDS = 150

    /** Randomised cooldown between batches, in minutes. */
    const val MIN_COOLDOWN_MINUTES = 8
    const val MAX_COOLDOWN_MINUTES = 15

    const val MIN_COOLDOWN_SECONDS = MIN_COOLDOWN_MINUTES * 60
    const val MAX_COOLDOWN_SECONDS = MAX_COOLDOWN_MINUTES * 60

    /**
     * Successful adds allowed per day. This belongs to the **phone number**, not
     * to one job — running three group-add jobs in a day shares one allowance of
     * 20, exactly as the send-side daily cap works (see `MEMORY.md`, "the daily
     * cap belongs to the phone number").
     */
    const val DAILY_CAP = 20

    /**
     * Consecutive failures that stop the run. Two, not the three the send-side
     * [com.tricreta.scopewa.brain.safety.CircuitBreaker] allows — layer 5 is
     * stricter than layer 4 on purpose.
     */
    const val MAX_CONSECUTIVE_FAILURES = 2

    /** Adds still allowed today given [addedToday] already done across all jobs. */
    fun remainingToday(addedToday: Int): Int = (DAILY_CAP - addedToday).coerceAtLeast(0)

    fun isDailyCapReached(addedToday: Int): Boolean = remainingToday(addedToday) == 0

    /** True once [addsInCurrentBatch] fills a batch and a cooldown is owed. */
    fun isBatchComplete(addsInCurrentBatch: Int): Boolean =
        addsInCurrentBatch > 0 && addsInCurrentBatch % BATCH_SIZE == 0

    /**
     * A plain-English summary for the confirmation screen. The client is paying
     * for this to be slow; a screen that hid how slow would look identical to
     * the tools that get numbers banned.
     */
    fun summary(): String =
        "$BATCH_SIZE at a time · $MIN_DELAY_SECONDS–${MAX_DELAY_SECONDS}s between each · " +
            "$MIN_COOLDOWN_MINUTES–$MAX_COOLDOWN_MINUTES min break between batches · " +
            "$DAILY_CAP a day, maximum"
}

/**
 * Draws the concrete randomised waits from [GroupAddPacing]'s ranges.
 *
 * Randomised on every call for the same reason [com.tricreta.scopewa.brain.pacing.PacingPlanner]
 * is: a fixed interval is itself a fingerprint (architecture doc section 6
 * layer 2), and a group-add loop ticking every 90.0 seconds is about as obvious
 * as automation gets.
 *
 * [random] is injectable so tests can pin the draw.
 */
class GroupAddPacer(private val random: Random = Random.Default) {

    /** Seconds to wait before the next add. Always inside 60–150s. */
    fun nextDelaySeconds(): Int =
        random.nextInt(GroupAddPacing.MIN_DELAY_SECONDS, GroupAddPacing.MAX_DELAY_SECONDS + 1)

    /** Seconds to wait after a full batch. Always inside 8–15 minutes. */
    fun nextCooldownSeconds(): Int =
        random.nextInt(GroupAddPacing.MIN_COOLDOWN_SECONDS, GroupAddPacing.MAX_COOLDOWN_SECONDS + 1)
}
