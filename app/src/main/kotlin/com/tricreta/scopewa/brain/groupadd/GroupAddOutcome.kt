package com.tricreta.scopewa.brain.groupadd

/**
 * Where one attempted add ends up. These are the "result buckets" the Group Add
 * screen shows (architecture doc section 7).
 */
enum class GroupAddBucket(val label: String) {
    Added("Added"),

    /**
     * Privacy-blocked. Architecture doc section 8 calls this out explicitly:
     * *"Privacy blocks group add → 'Needs invite' bucket + one-tap invite link.
     * **Not a bug.**"*
     */
    NeedsInvite("Needs invite"),

    Failed("Failed"),
    Skipped("Skipped"),

    /** WhatsApp warned about the account — stops everything, not just this person. */
    Restricted("Restricted")
}

/**
 * What happened to one attempted group add.
 *
 * Mirrors [com.tricreta.scopewa.accessibility.SendOutcome]'s shape, with three
 * differences that matter:
 *
 * 1. **[NeedsInviteLink] is terminal.** A privacy-blocked number is not a
 *    transient error to retry — WhatsApp's "who can add me to groups" setting
 *    is a decision the recipient made, and hammering it is both futile and a
 *    ban signal. It goes to the invite bucket and never re-enters the queue.
 *    [isRetryable] is the property that enforces that.
 * 2. **The failure breaker is two deep, not three** — see
 *    [GroupAddPacing.MAX_CONSECUTIVE_FAILURES].
 * 3. **A skip is never a failure**, exactly as on the send side. Skipping
 *    someone opted out is the anti-ban system working; counting it toward the
 *    breaker would stop a run for doing the right thing.
 *
 * Deliberately free of Android imports so the bucketing rules are unit tested
 * in CI without a phone.
 */
sealed interface GroupAddOutcome {

    /** Confirmed in the group — the participant list or count changed. */
    data class Added(val elapsedMillis: Long) : GroupAddOutcome

    /**
     * WhatsApp said this person can't be added because of their privacy
     * settings, and offered an invite link instead. **Terminal.**
     */
    data class NeedsInviteLink(val matchedText: String) : GroupAddOutcome

    /** They were already in the group. Nothing to do, and not an error. */
    data class AlreadyInGroup(val detail: String = "") : GroupAddOutcome

    /** Deliberately not attempted — opted out, cold, or already handled. */
    data class Skipped(val reason: String) : GroupAddOutcome

    /** WhatsApp isn't installed, isn't in front, or the service isn't bound. */
    data class NotReady(val reason: String) : GroupAddOutcome

    /** The group, the group-info screen, or the Add-participants button wasn't found. */
    data class GroupNotReached(val reason: String) : GroupAddOutcome

    /** The number was typed but no contact row came back to tap. */
    data class NumberNotFound(val phoneE164: String) : GroupAddOutcome

    /** The taps happened but the participant list never showed them. */
    data class NotConfirmed(val reason: String) : GroupAddOutcome

    /**
     * WhatsApp warned about the account. Like the send path, this stops the
     * whole job rather than counting as one more failure — architecture doc
     * section 6 layer 4.
     */
    data class Restricted(val matchedText: String) : GroupAddOutcome

    val bucket: GroupAddBucket
        get() = when (this) {
            is Added -> GroupAddBucket.Added
            is NeedsInviteLink -> GroupAddBucket.NeedsInvite
            is AlreadyInGroup, is Skipped -> GroupAddBucket.Skipped
            is Restricted -> GroupAddBucket.Restricted
            is NotReady, is GroupNotReached, is NumberNotFound, is NotConfirmed ->
                GroupAddBucket.Failed
        }

    val isAdded: Boolean get() = this is Added

    /**
     * Counts toward the two-consecutive-failure hard stop. Adds, skips,
     * already-in-group and privacy blocks all do **not**: none of them is
     * evidence that the automation is broken, which is the only thing the
     * breaker is trying to detect.
     */
    val isFailure: Boolean get() = bucket == GroupAddBucket.Failed

    /** True only when this outcome should stop the entire job immediately. */
    val stopsEverything: Boolean get() = this is Restricted

    /**
     * Whether this person may go back into a queue later. **False for
     * [NeedsInviteLink]** — that is the whole point of the invite bucket.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is NeedsInviteLink, is Added, is AlreadyInGroup -> false
            else -> true
        }

    /** A sentence for the results list and the job's `last_failure` column. */
    fun describe(): String = when (this) {
        is Added -> "Added to the group"
        is NeedsInviteLink ->
            "Their privacy settings don't allow being added — send them the invite link instead"
        is AlreadyInGroup -> if (detail.isBlank()) "Already in the group" else detail
        is Skipped -> reason
        is NotReady -> reason
        is GroupNotReached -> reason
        is NumberNotFound ->
            "WhatsApp didn't find $phoneE164 — they may not be on WhatsApp, or the number is wrong"
        is NotConfirmed -> reason
        is Restricted -> "WhatsApp warning: \"$matchedText\""
    }
}
