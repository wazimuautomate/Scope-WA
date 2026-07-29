package com.tricreta.scopewa.brain.groupadd

/**
 * Why this person is safe — or not safe — to add to a group.
 *
 * Architecture doc section 6 layer 5 adds a rule the client's browser extension
 * does not have:
 *
 * > **only offer to add people who have messaged him before or are in a list he
 * > extracted from a group they already joined.** Adding cold numbers is what
 * > gets numbers killed.
 *
 * So provenance is not decoration on a contact — it is the gate. A candidate
 * with no provenance is [Cold] and is never offered.
 */
sealed interface AddProvenance {

    /** Short label for the UI. */
    val label: String

    /**
     * Round-trippable form, so provenance survives being written to
     * `group_add_jobs.provenance` and read back. The stored value is the audit
     * trail for "why was this person offered at all", so it has to be the real
     * provenance, not a rendered sentence.
     */
    val code: String

    /** True when architecture doc section 6 layer 5 allows adding this person. */
    val isEligible: Boolean

    /** They have replied at least once — the strongest signal there is. */
    data object RepliedBefore : AddProvenance {
        override val label: String = "Has messaged you before"
        override val code: String = "REPLIED"
        override val isEligible: Boolean = true
    }

    /**
     * They came out of a group extraction (Phase 4 writes the group name into
     * `contacts.source_group`), so they already share a group with the user.
     */
    data class SharedGroup(val groupName: String) : AddProvenance {
        override val label: String
            get() = if (groupName.isBlank()) "From a group you're both in" else "From \"$groupName\""
        override val code: String get() = "$GROUP_PREFIX$groupName"
        override val isEligible: Boolean = true
    }

    /** Imported from a file, never replied, no shared group. Never addable. */
    data object Cold : AddProvenance {
        override val label: String = "Cold number"
        override val code: String = "COLD"
        override val isEligible: Boolean = false
    }

    companion object {
        private const val GROUP_PREFIX = "GROUP:"

        /**
         * Anything unrecognised decodes to [Cold] on purpose. A provenance we
         * can't read is not evidence that someone is safe to add, and the
         * failure mode of this rule has to be "refuse", never "assume".
         */
        fun fromCode(code: String?): AddProvenance = when {
            code == RepliedBefore.code -> RepliedBefore
            code != null && code.startsWith(GROUP_PREFIX) ->
                SharedGroup(code.removePrefix(GROUP_PREFIX))
            else -> Cold
        }
    }
}

/** One person the user could be offered for a group add. */
data class AddCandidate(
    val phoneE164: String,
    val displayName: String = "",
    val provenance: AddProvenance = AddProvenance.Cold,
    /** Contact row this came from, so results can be written back. 0 when unknown. */
    val contactId: Long = 0
) {
    /** What to show in a list — never a blank row. */
    val label: String get() = displayName.ifBlank { phoneE164 }
}

/** A candidate that will not be added, and the sentence explaining why. */
data class RejectedCandidate(
    val candidate: AddCandidate,
    val reason: String,
    /** True when the rejection is the cold-number rule rather than bad data. */
    val isCold: Boolean
)

/** The result of screening a source list. */
data class EligibilitySplit(
    val eligible: List<AddCandidate>,
    val rejected: List<RejectedCandidate>
) {
    val eligibleCount: Int get() = eligible.size
    val rejectedCount: Int get() = rejected.size
    val coldCount: Int get() = rejected.count { it.isCold }
    val totalConsidered: Int get() = eligibleCount + rejectedCount

    /** Nobody survived screening — the Start button must stay off. */
    val hasNobody: Boolean get() = eligible.isEmpty()
}

/**
 * The hard rule from architecture doc section 6 layer 5, as pure logic.
 *
 * This is a safety feature, so it is deliberately *loud* rather than silent:
 * [partition] hands back every rejection with a human-readable reason so the
 * Group Add screen can show "34 of 200 can be added — 166 were cold numbers"
 * instead of quietly shipping a shorter list than the user picked.
 */
object GroupAddEligibility {

    const val COLD_REASON: String =
        "Never messaged you and not from a group you share — adding cold numbers " +
            "is the fastest way to lose the number"

    const val NO_NUMBER_REASON: String = "No usable phone number"

    const val OPTED_OUT_REASON: String =
        "Opted out — someone who asked to be left alone must not be pulled into a group"

    fun isEligible(candidate: AddCandidate): Boolean =
        candidate.phoneE164.isNotBlank() && candidate.provenance.isEligible

    /**
     * Screens [candidates] into who may be added and who may not.
     *
     * Duplicates are collapsed on [AddCandidate.phoneE164] keeping the
     * strongest provenance, because the same person appearing in two source
     * groups must not be added twice — WhatsApp counts the second attempt as a
     * failure and the failure breaker is only two deep.
     *
     * @param optedOutNumbers numbers on the suppression list. Opting out of
     *   messages also opts out of being added to a group; treating those as
     *   separate consents is the kind of technicality that gets an app reported.
     */
    fun partition(
        candidates: List<AddCandidate>,
        optedOutNumbers: Set<String> = emptySet()
    ): EligibilitySplit {
        val eligible = mutableListOf<AddCandidate>()
        val rejected = mutableListOf<RejectedCandidate>()

        for (candidate in dedupe(candidates)) {
            when {
                candidate.phoneE164.isBlank() ->
                    rejected += RejectedCandidate(candidate, NO_NUMBER_REASON, isCold = false)

                candidate.phoneE164 in optedOutNumbers ->
                    rejected += RejectedCandidate(candidate, OPTED_OUT_REASON, isCold = false)

                !candidate.provenance.isEligible ->
                    rejected += RejectedCandidate(candidate, COLD_REASON, isCold = true)

                else -> eligible += candidate
            }
        }

        return EligibilitySplit(eligible = eligible, rejected = rejected)
    }

    /**
     * First appearance wins position; the best provenance wins the row. A
     * contact that replied *and* came from a shared group should read as the
     * former, which is the stronger claim.
     */
    private fun dedupe(candidates: List<AddCandidate>): List<AddCandidate> {
        val byNumber = LinkedHashMap<String, AddCandidate>()
        var blankIndex = 0
        for (candidate in candidates) {
            if (candidate.phoneE164.isBlank()) {
                // Blank numbers can't be keyed by number, and collapsing them
                // together would under-report how much of the file was junk.
                byNumber["<blank-${blankIndex++}>"] = candidate
                continue
            }
            val existing = byNumber[candidate.phoneE164]
            byNumber[candidate.phoneE164] = when {
                existing == null -> candidate
                strength(candidate.provenance) > strength(existing.provenance) ->
                    existing.copy(provenance = candidate.provenance)
                else -> existing
            }
        }
        return byNumber.values.toList()
    }

    private fun strength(provenance: AddProvenance): Int = when (provenance) {
        AddProvenance.RepliedBefore -> 2
        is AddProvenance.SharedGroup -> 1
        AddProvenance.Cold -> 0
    }
}
