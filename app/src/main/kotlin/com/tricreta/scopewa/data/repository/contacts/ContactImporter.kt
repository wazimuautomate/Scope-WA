package com.tricreta.scopewa.data.repository.contacts

import com.tricreta.scopewa.brain.phone.NormalizedPhone
import com.tricreta.scopewa.brain.phone.PhoneNormalizer
import com.tricreta.scopewa.data.repository.contacts.parse.ParsedContact

/** A row that survived normalisation and is ready to become a contact. */
data class ImportCandidate(
    val phoneE164: String,
    val rawNumber: String,
    val name: String,
    val fields: Map<String, String> = emptyMap()
)

/** A row that couldn't be turned into a number, kept so the user sees *why*. */
data class RejectedRow(
    val rawNumber: String,
    val name: String,
    val reason: RejectReason
)

enum class RejectReason {
    /** No number in the row at all. */
    Blank,

    /** Characters, but no digits — a stray header row, a note, an email. */
    NotANumber,

    /** Digits, but too few or too many to be a real phone number. */
    BadLength
}

/**
 * What an import would do, worked out *before* anything is written. The import
 * screen shows these counts and the user confirms — importing 20,000 rows and
 * then explaining what happened is much worse than explaining first.
 */
data class ImportPlan(
    val newContacts: List<ImportCandidate> = emptyList(),
    val existingContacts: List<ImportCandidate> = emptyList(),
    val suppressed: List<ImportCandidate> = emptyList(),
    val rejected: List<RejectedRow> = emptyList(),
    val duplicatesInFile: Int = 0
) {
    /** Everything that will end up in the database or the target list. */
    val importable: List<ImportCandidate> get() = newContacts + existingContacts

    val rowsRead: Int
        get() = newContacts.size + existingContacts.size + suppressed.size +
            rejected.size + duplicatesInFile

    val isEmpty: Boolean get() = importable.isEmpty()
}

/**
 * Turns parsed file rows into an [ImportPlan]: normalise every number through
 * the Phase 0 [PhoneNormalizer], drop duplicates, and split the rest into new /
 * already-known / suppressed / rejected.
 *
 * **Suppressed numbers are dropped, not imported.** That's the whole point of
 * the suppression list and the `opted_out` flag in architecture doc section 6
 * layer 3 — someone who replied STOP must not quietly reappear in a list
 * because the user re-imported the same CSV.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
class ContactImporter(
    private val normalizer: PhoneNormalizer = PhoneNormalizer()
) {

    fun plan(
        rows: List<ParsedContact>,
        existingNumbers: Set<String> = emptySet(),
        suppressedNumbers: Set<String> = emptySet()
    ): ImportPlan {
        val rejected = mutableListOf<RejectedRow>()
        val byNumber = LinkedHashMap<String, ImportCandidate>()
        var duplicates = 0

        for (row in rows) {
            when (val normalized = normalizer.normalize(row.rawNumber)) {
                is NormalizedPhone.Invalid -> rejected.add(
                    RejectedRow(row.rawNumber.trim(), row.name.trim(), reasonFor(row.rawNumber))
                )

                is NormalizedPhone.Valid -> {
                    val candidate = ImportCandidate(
                        phoneE164 = normalized.e164,
                        rawNumber = row.rawNumber.trim(),
                        name = row.name.trim(),
                        fields = row.fields
                    )
                    val previous = byNumber[normalized.e164]
                    if (previous == null) {
                        byNumber[normalized.e164] = candidate
                    } else {
                        duplicates++
                        byNumber[normalized.e164] = merge(previous, candidate)
                    }
                }
            }
        }

        val newContacts = mutableListOf<ImportCandidate>()
        val existing = mutableListOf<ImportCandidate>()
        val suppressed = mutableListOf<ImportCandidate>()

        for (candidate in byNumber.values) {
            when {
                candidate.phoneE164 in suppressedNumbers -> suppressed.add(candidate)
                candidate.phoneE164 in existingNumbers -> existing.add(candidate)
                else -> newContacts.add(candidate)
            }
        }

        return ImportPlan(
            newContacts = newContacts,
            existingContacts = existing,
            suppressed = suppressed,
            rejected = rejected,
            duplicatesInFile = duplicates
        )
    }

    /**
     * Same number twice in one file: keep the first, but let a later row fill
     * in anything the first one was missing. Ported from `dedupe()` in
     * `docs/reference/whatsapp-group-adder/lib/parse.js`, which keeps whichever
     * copy has a name.
     */
    private fun merge(kept: ImportCandidate, duplicate: ImportCandidate): ImportCandidate =
        kept.copy(
            name = kept.name.ifBlank { duplicate.name },
            fields = duplicate.fields + kept.fields // first-seen values win
        )

    private fun reasonFor(raw: String): RejectReason = when {
        raw.isBlank() -> RejectReason.Blank
        raw.none(Char::isDigit) -> RejectReason.NotANumber
        else -> RejectReason.BadLength
    }
}
