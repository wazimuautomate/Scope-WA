package com.tricreta.scopewa.brain.campaign

/** One candidate recipient, flattened out of the contacts table. */
data class RecipientCandidate(
    val contactId: Long,
    val phoneE164: String,
    val displayName: String,
    val isSaved: Boolean = false,
    val timesReplied: Int = 0,
    val optedOut: Boolean = false,
    val lastMessagedAt: Long? = null,
    val fields: Map<String, String> = emptyMap()
)

/** Why someone was dropped from the queue before it was even built. */
enum class SkipReason {
    /** Replied STOP/ACHA/SITAKI, or is on the suppression list. */
    OptedOut,

    /** Messaged too recently — the per-person cooldown in section 6 layer 3. */
    WithinCooldown,

    /** The same number appears twice in the source list. */
    DuplicateInList
}

data class SkippedRecipient(val candidate: RecipientCandidate, val reason: SkipReason)

/** An ordered send queue, plus everyone deliberately left out of it. */
data class RecipientPlan(
    val queue: List<RecipientCandidate> = emptyList(),
    val skipped: List<SkippedRecipient> = emptyList()
) {
    val queuedCount: Int get() = queue.size
    val skippedCount: Int get() = skipped.size
}

/**
 * Turns a contact list into the order a human would plausibly message people
 * in — architecture doc section 6 layer 3, "recipient hygiene", which is where
 * the real ban risk lives.
 *
 * Two rules, in this order:
 *
 * 1. **People who replied before go first.** A two-way conversation is the
 *    strongest positive signal WhatsApp has about an account.
 * 2. **Then saved contacts.** Messages to people who have you in their phone
 *    are dramatically safer than messages to strangers.
 *
 * Strangers go last, so if a campaign is going to be cut short by a circuit
 * breaker or the daily cap, the messages that *did* go out are the safest ones.
 * That is the whole point of ordering rather than shuffling.
 *
 * Ordering is stable within each tier: the source list's own order survives, so
 * a user who deliberately arranged a list still gets what they arranged.
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
object RecipientOrdering {

    /**
     * @param candidates      everyone on the chosen list
     * @param suppressedNumbers E.164 numbers that must never be messaged
     * @param nowMillis       current time, injected so tests aren't clock-dependent
     * @param cooldownDays    never message the same person twice inside this many days
     */
    fun plan(
        candidates: List<RecipientCandidate>,
        suppressedNumbers: Set<String> = emptySet(),
        nowMillis: Long = 0L,
        cooldownDays: Int = DEFAULT_COOLDOWN_DAYS
    ): RecipientPlan {
        val eligible = mutableListOf<RecipientCandidate>()
        val skipped = mutableListOf<SkippedRecipient>()
        val seen = mutableSetOf<String>()
        val cooldownMillis = cooldownDays.toLong() * MILLIS_PER_DAY

        for (candidate in candidates) {
            when {
                // Opt-out is checked first so the *reason* shown to the user is
                // the important one. A duplicate of an opted-out number should
                // read "opted out", not "duplicate" — same outcome, but only
                // one of those tells the user something they can act on.
                candidate.optedOut || candidate.phoneE164 in suppressedNumbers ->
                    skipped.add(SkippedRecipient(candidate, SkipReason.OptedOut))

                !seen.add(candidate.phoneE164) ->
                    skipped.add(SkippedRecipient(candidate, SkipReason.DuplicateInList))

                isWithinCooldown(candidate.lastMessagedAt, nowMillis, cooldownMillis) ->
                    skipped.add(SkippedRecipient(candidate, SkipReason.WithinCooldown))

                else -> eligible.add(candidate)
            }
        }

        return RecipientPlan(queue = order(eligible), skipped = skipped)
    }

    /**
     * Replied-before first, then saved, then everyone else — stable within each
     * tier. Exposed on its own because the campaign composer previews the
     * ordering before anything is written.
     */
    fun order(candidates: List<RecipientCandidate>): List<RecipientCandidate> =
        candidates.withIndex()
            .sortedWith(compareBy({ tierOf(it.value) }, { it.index }))
            .map { it.value }

    /** 0 = replied before, 1 = saved contact, 2 = stranger. */
    fun tierOf(candidate: RecipientCandidate): Int = when {
        candidate.timesReplied > 0 -> TIER_REPLIED
        candidate.isSaved -> TIER_SAVED
        else -> TIER_STRANGER
    }

    private fun isWithinCooldown(lastMessagedAt: Long?, nowMillis: Long, cooldownMillis: Long): Boolean {
        if (lastMessagedAt == null || cooldownMillis <= 0L) return false
        // A clock that went backwards must not silently unlock everyone.
        return nowMillis - lastMessagedAt < cooldownMillis
    }

    const val TIER_REPLIED = 0
    const val TIER_SAVED = 1
    const val TIER_STRANGER = 2

    /** Section 6 layer 3: "never message the same person twice within N days". */
    const val DEFAULT_COOLDOWN_DAYS = 7

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
