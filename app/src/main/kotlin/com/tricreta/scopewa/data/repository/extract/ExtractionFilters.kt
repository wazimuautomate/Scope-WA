package com.tricreta.scopewa.data.repository.extract

/**
 * The filter set ported from the proven Chrome extension
 * (`docs/reference/whatsapp-contact-extractor`, architecture doc section 3.2):
 * exclude admins, exclude already-saved, hide privacy-hidden.
 *
 * @param excludeAdmins group admins are usually the people you already know,
 *   and are the most likely to report a stranger's message.
 * @param excludeSaved skip anyone already in the contacts database. Extraction
 *   is for harvesting *unknown* numbers.
 * @param excludeWithoutNumbers drop members whose number wasn't readable. **Off
 *   by default** — the point of this screen is to capture everyone in the
 *   group, not just the subset WhatsApp happens to show a number for. A member
 *   without a number still gets a row (their saved/push name, or a fallback —
 *   see [MemberRowParser]); it just can't be messaged, since there is no
 *   number to send to. That's a hard limit, not a filtering choice — turning
 *   this on hides those rows instead of producing a number for them, which
 *   doesn't exist to find. See [MemberNumberStatus] for why.
 * @param excludeSelf never export the account running the extraction.
 */
data class ExtractionFilters(
    val excludeAdmins: Boolean = false,
    val excludeSaved: Boolean = false,
    val excludeWithoutNumbers: Boolean = false,
    val excludeSelf: Boolean = true
)

/** What a filter pass kept and, importantly, what it dropped and why. */
data class FilterOutcome(
    val kept: List<ExtractedMember>,
    val droppedAdmins: Int = 0,
    val droppedSaved: Int = 0,
    val droppedWithoutNumbers: Int = 0,
    val droppedSelf: Int = 0
) {
    val totalDropped: Int
        get() = droppedAdmins + droppedSaved + droppedWithoutNumbers + droppedSelf
}

object ExtractionFilter {

    /**
     * Applies [filters], reporting per-reason drop counts.
     *
     * The counts are the point. "824 members → 310 exported" is alarming with
     * no explanation and unremarkable once it reads "310 exported, 400 numbers
     * not shown, 114 already saved" — and the difference between those two
     * presentations is whether the user trusts the export.
     *
     * @param savedNumbers E.164 numbers already in the contacts database, used
     *   for [ExtractionFilters.excludeSaved].
     */
    fun apply(
        members: List<ExtractedMember>,
        filters: ExtractionFilters,
        savedNumbers: Set<String> = emptySet()
    ): FilterOutcome {
        var droppedAdmins = 0
        var droppedSaved = 0
        var droppedWithoutNumbers = 0
        var droppedSelf = 0

        val kept = members.filter { member ->
            when {
                filters.excludeSelf && member.isSelf -> {
                    droppedSelf++; false
                }
                filters.excludeAdmins && member.isAdmin -> {
                    droppedAdmins++; false
                }
                filters.excludeWithoutNumbers && !member.hasNumber -> {
                    droppedWithoutNumbers++; false
                }
                filters.excludeSaved && member.phoneE164 != null &&
                    member.phoneE164 in savedNumbers -> {
                    droppedSaved++; false
                }
                else -> true
            }
        }

        return FilterOutcome(
            kept = kept,
            droppedAdmins = droppedAdmins,
            droppedSaved = droppedSaved,
            droppedWithoutNumbers = droppedWithoutNumbers,
            droppedSelf = droppedSelf
        )
    }
}
