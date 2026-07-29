package com.tricreta.scopewa.data.repository.extract

import com.tricreta.scopewa.brain.phone.NormalizedPhone
import com.tricreta.scopewa.brain.phone.PhoneNormalizer

/**
 * Turns one rendered participant row into an [ExtractedMember].
 *
 * Pure Kotlin with no Android imports, so every rule below is unit tested in
 * CI — which matters because this is where a WhatsApp layout change does its
 * quietest damage. A selector that breaks outright is obvious; a parser that
 * starts reading status text as phone numbers is not.
 *
 * @param defaultCountryCode used when a row shows a local number without a
 *   country code. Defaults to Kenya, matching
 *   [com.tricreta.scopewa.brain.phone.PhoneNormalizer].
 */
class MemberRowParser(
    defaultCountryCode: String = "254",
    private val selfLabels: Set<String> = DEFAULT_SELF_LABELS,
    private val adminLabels: Set<String> = DEFAULT_ADMIN_LABELS
) {

    private val normalizer = PhoneNormalizer(defaultCountryCode)

    /**
     * @param title the row's primary line — a phone number for unsaved
     *   contacts, a name for saved ones.
     * @param subtitle the secondary line, usually the "about" text. Occasionally
     *   carries the number on some layouts, so it is checked as a fallback.
     * @param adminLabel any separate admin badge text found in the row.
     */
    fun parse(
        title: String?,
        subtitle: String? = null,
        adminLabel: String? = null
    ): ExtractedMember? {
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isEmpty()) return null

        val cleanSubtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() }

        val isSelf = selfLabels.any { cleanTitle.equals(it, ignoreCase = true) }
        val isAdmin = isAdminRow(adminLabel, cleanSubtitle)

        // Prefer a number in the title (that's where WhatsApp puts it for
        // unsaved contacts). Only fall back to the subtitle, never to arbitrary
        // digits elsewhere — an "about" text like "Call me on 0712345678" is a
        // real risk of harvesting the wrong number.
        val number = extractNumber(cleanTitle) ?: cleanSubtitle?.let { extractNumber(it) }

        return ExtractedMember(
            rawTitle = cleanTitle,
            rawSubtitle = cleanSubtitle,
            displayName = displayNameFor(cleanTitle, cleanSubtitle, number),
            phoneE164 = number,
            numberStatus = if (number != null) MemberNumberStatus.Visible else MemberNumberStatus.NotShown,
            isAdmin = isAdmin,
            isSelf = isSelf
        )
    }

    private fun isAdminRow(adminLabel: String?, subtitle: String?): Boolean {
        val candidates = listOfNotNull(adminLabel?.trim(), subtitle)
        return candidates.any { candidate ->
            adminLabels.any { candidate.equals(it, ignoreCase = true) }
        }
    }

    /**
     * A saved contact's row title is their name; an unsaved one's is their
     * number. When all we have is the number, use it as the label rather than
     * leaving the name blank — an export row with an empty name column is
     * harder to read than one that repeats the number.
     *
     * `~Push Name` is WhatsApp's marker for a name the *user themselves* set,
     * shown for people not in the phonebook. The tilde is stripped.
     */
    private fun displayNameFor(title: String, subtitle: String?, number: String?): String {
        val titleIsJustANumber = number != null && extractNumber(title) != null

        if (!titleIsJustANumber) {
            val stripped = title.removePrefix("~").trim()
            // A title that was literally just "~" strips to nothing; fall back
            // to the raw title rather than hand back a blank name.
            return stripped.ifEmpty { title }
        }

        val pushName = subtitle?.takeIf { it.startsWith("~") }?.removePrefix("~")?.trim()
        return pushName?.takeIf { it.isNotEmpty() } ?: title
    }

    /**
     * Pulls a phone number out of [text] if the text is *predominantly* a
     * number, and normalises it.
     *
     * Deliberately strict. It matches a leading `+`/digit run of separators and
     * digits and then requires that the match account for most of the string,
     * so "Available" and "Nairobi 2024" don't become contacts. Loose matching
     * here would poison the contact database with junk that later gets messaged.
     */
    private fun extractNumber(text: String): String? {
        val match = PHONE_PATTERN.find(text) ?: return null
        val candidate = match.value.trim()

        // Reject when the number is only a fragment of a longer sentence.
        if (candidate.length < text.trim().length * MIN_NUMERIC_SHARE) return null

        return when (val normalized = normalizer.normalize(candidate)) {
            is NormalizedPhone.Valid -> normalized.e164
            is NormalizedPhone.Invalid -> null
        }
    }

    private companion object {
        /** `+254 712-345 678`, `0712345678`, `(254) 712 345 678`. */
        val PHONE_PATTERN = Regex("""\+?\d[\d\s\-().]{5,}\d""")

        /**
         * The matched number must be at least this share of the whole string.
         * A row reading "Hi, ring 0712345678 anytime" is an about-text, not a
         * participant's number.
         */
        const val MIN_NUMERIC_SHARE = 0.6

        val DEFAULT_SELF_LABELS = setOf("You", "you")

        /** Localised, so best-effort — see the ordering rules on `WaSelectors`. */
        val DEFAULT_ADMIN_LABELS = setOf("Group admin", "Admin", "Super admin")
    }
}
