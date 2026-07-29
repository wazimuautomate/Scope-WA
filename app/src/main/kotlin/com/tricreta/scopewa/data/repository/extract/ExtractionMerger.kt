package com.tricreta.scopewa.data.repository.extract

/** One person across every group they were found in. */
data class MergedMember(
    val phoneE164: String?,
    val displayName: String,
    /** Every group this person appeared in, in encounter order. */
    val sourceGroups: List<String>,
    /** True if they were an admin in *any* source group. */
    val isAdminAnywhere: Boolean,
    val numberStatus: MemberNumberStatus
) {
    val hasNumber: Boolean get() = phoneE164 != null
    val isInMultipleGroups: Boolean get() = sourceGroups.size > 1
}

/** Result of merging several groups' extractions. */
data class MergedExtraction(
    val members: List<MergedMember>,
    /** Rows collapsed because the same person was in more than one group. */
    val duplicatesCollapsed: Int,
    val groupsMerged: List<String>
) {
    val withNumbers: List<MergedMember> get() = members.filter { it.hasNumber }
}

/**
 * Dedupes members across groups — the "dedupe-across-groups" behaviour the
 * build plan lists for Phase 4, inherited from the Chrome extension.
 *
 * This matters at the client's scale: 150+ groups of 700+ people
 * (architecture doc section 10, Q7) overlap heavily, and messaging the same
 * person once per group they happen to share with you is both wasteful and,
 * per architecture doc section 6 layer 3, a fast route to being blocked.
 */
object ExtractionMerger {

    /**
     * Merges [extractions] into one deduped list.
     *
     * Identity is the normalised phone number. Members whose number wasn't
     * readable **cannot be deduped** — two rows both showing only "John" may or
     * may not be the same person — so they are passed through individually
     * rather than collapsed on name, which would silently drop real people.
     */
    fun merge(extractions: List<GroupExtraction>): MergedExtraction {
        val byNumber = LinkedHashMap<String, MergedMember>()
        val withoutNumbers = mutableListOf<MergedMember>()
        var duplicatesCollapsed = 0

        for (extraction in extractions) {
            for (member in extraction.members) {
                val number = member.phoneE164

                if (number == null) {
                    withoutNumbers += MergedMember(
                        phoneE164 = null,
                        displayName = member.displayName,
                        sourceGroups = listOf(extraction.groupName),
                        isAdminAnywhere = member.isAdmin,
                        numberStatus = member.numberStatus
                    )
                    continue
                }

                val existing = byNumber[number]
                if (existing == null) {
                    byNumber[number] = MergedMember(
                        phoneE164 = number,
                        displayName = member.displayName,
                        sourceGroups = listOf(extraction.groupName),
                        isAdminAnywhere = member.isAdmin,
                        numberStatus = member.numberStatus
                    )
                } else {
                    duplicatesCollapsed++
                    byNumber[number] = existing.copy(
                        // Prefer a real name over a bare number, whichever group it came from.
                        displayName = preferredName(existing.displayName, member.displayName, number),
                        sourceGroups = if (extraction.groupName in existing.sourceGroups) {
                            existing.sourceGroups
                        } else {
                            existing.sourceGroups + extraction.groupName
                        },
                        isAdminAnywhere = existing.isAdminAnywhere || member.isAdmin
                    )
                }
            }
        }

        return MergedExtraction(
            members = byNumber.values.toList() + withoutNumbers,
            duplicatesCollapsed = duplicatesCollapsed,
            groupsMerged = extractions.map { it.groupName }.distinct()
        )
    }

    /**
     * A person can appear as their number in one group and as a push name in
     * another. Keep whichever is actually a name.
     */
    private fun preferredName(current: String, candidate: String, number: String): String {
        val currentIsJustNumber = current.isBlank() || current.filter { it.isDigit() } ==
            number.filter { it.isDigit() }
        val candidateIsUseful = candidate.isNotBlank() &&
            candidate.filter { it.isDigit() } != number.filter { it.isDigit() }

        return if (currentIsJustNumber && candidateIsUseful) candidate else current
    }
}
