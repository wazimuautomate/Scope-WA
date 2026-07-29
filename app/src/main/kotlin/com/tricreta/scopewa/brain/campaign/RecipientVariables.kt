package com.tricreta.scopewa.brain.campaign

/**
 * Builds the variable map one recipient's message is rendered with —
 * architecture doc section 6 layer 1, "any column in his CSV becomes a
 * variable".
 *
 * Three sources, and the precedence between them is the interesting part:
 *
 * 1. **Automatic values** (`{date}`, `{random_number}`, …) go in first.
 * 2. **CSV columns imported with the contact** override them, because a column
 *    the client actually named beats a built-in that happens to share the name.
 * 3. **Identity values derived from the contact** (`{name}`, `{first_name}`,
 *    `{phone}`) go in last and win outright — a stale `name` column left in a
 *    CSV must never address someone by the wrong name.
 *
 * Keys are lowercased and trimmed to match `TemplateEngine`, which does the
 * same to whatever it finds in the template.
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
object RecipientVariables {

    fun forRecipient(
        recipient: RecipientCandidate,
        automaticValues: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()

        automaticValues.forEach { (key, value) -> merged[normalise(key)] = value }
        recipient.fields.forEach { (key, value) -> merged[normalise(key)] = value }

        val name = recipient.displayName.trim()
        merged["name"] = name
        merged["first_name"] = firstNameOf(name)
        merged["last_name"] = lastNameOf(name)
        merged["phone"] = recipient.phoneE164

        return merged
    }

    /**
     * The names a template may legitimately use for this campaign. Passed to
     * `TemplateEngine.render` as `knownVariableNames`, which is what stops
     * `{Hi|Hello}` spintax being mistaken for a variable with a fallback.
     */
    fun knownNames(
        recipients: List<RecipientCandidate>,
        automaticNames: Collection<String> = emptyList()
    ): Set<String> = buildSet {
        addAll(IDENTITY_NAMES)
        automaticNames.forEach { add(normalise(it)) }
        recipients.forEach { recipient -> recipient.fields.keys.forEach { add(normalise(it)) } }
    }

    /** "Joy Wanjiru Mwangi" → "Joy". Blank stays blank so `{first_name|there}` can do its job. */
    fun firstNameOf(fullName: String): String =
        fullName.trim().substringBefore(' ').trim()

    /** "Joy Wanjiru Mwangi" → "Mwangi". Empty for a single-word name. */
    fun lastNameOf(fullName: String): String {
        val trimmed = fullName.trim()
        if (!trimmed.contains(' ')) return ""
        return trimmed.substringAfterLast(' ').trim()
    }

    private fun normalise(key: String) = key.trim().lowercase()

    private val IDENTITY_NAMES = setOf("name", "first_name", "last_name", "phone")
}
