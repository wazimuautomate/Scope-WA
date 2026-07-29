package com.tricreta.scopewa.brain.uniqueness

/**
 * The exact wording of the uniqueness meter from architecture doc section 6,
 * layer 1:
 *
 * ```
 * 200 messages · 194 unique (97%) · 6 exact duplicates
 * ⚠ 6 people would get identical text. Add more spintax options.
 * ```
 *
 * Kept next to the scorer, and unit tested, because the wording is the part of
 * the meter the client actually reads — "warn loudly" is a requirement, not a
 * styling choice.
 */

val UniquenessResult.duplicateCount: Int
    get() = total - uniqueCount

fun UniquenessResult.summaryLine(): String =
    "$total messages · $uniqueCount unique ($uniquePercent%) · $duplicateCount exact duplicates"

/** Null when every recipient gets text nobody else does. */
fun UniquenessResult.warningLine(): String? {
    if (!hasWarning) return null
    val people = if (duplicateCount == 1) "person" else "people"
    return "$duplicateCount $people would get identical text. Add more spintax options."
}
