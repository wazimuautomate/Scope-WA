package com.tricreta.scopewa.brain.template

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One placeholder the template editor offers as a chip — screenshot 11's
 * variable reference, trimmed to what this app can honestly supply.
 */
data class TemplateVariable(
    val name: String,
    val description: String,
    val sampleValue: String,
    val source: Source
) {
    /** What gets inserted into the message body when the chip is tapped. */
    val placeholder: String get() = "{$name}"

    enum class Source {
        /** Comes from a column in the contact list's CSV. */
        Csv,

        /** Computed at send time from the clock — no CSV column needed. */
        Automatic
    }
}

/**
 * The variable catalogue behind the editor's chips.
 *
 * Two things live here that the editor cannot work without:
 *
 * 1. **Which names are "known variables".** `TemplateEngine` decides whether
 *    `{name|there}` means "the CSV value, falling back to *there*" or spintax
 *    ("pick *name* or *there* at random") purely by whether the first segment
 *    is a known variable name. The editor has to hand the engine the same set
 *    the sender will, or the preview lies.
 * 2. **Sample values**, so the live preview shows a real-looking message
 *    instead of raw braces.
 *
 * Screenshot 11's `{LOCATION_*}` and `{BATT}` variables are deliberately
 * absent: they need Android APIs, and `brain/` stays Android-free so it can be
 * unit tested in CI without a phone (see `CLAUDE.md`).
 */
object TemplateVariables {

    private const val DATE_PATTERN = "d MMM yyyy"

    /**
     * Suggested CSV columns. Any column in the client's CSV works as a
     * variable — these are just the ones worth putting on a chip, taken from
     * the real message in architecture doc section 10, Q5.
     */
    val csv: List<TemplateVariable> = listOf(
        variable("name", "Full name from your CSV", "Amina Otieno"),
        variable("first_name", "First name only", "Amina"),
        variable("last_name", "Last name only", "Otieno"),
        variable("phone", "The recipient's number", "+254712345678"),
        variable("town", "Any other column — town, plan, agent…", "Nakuru"),
        variable("receipt_code", "Per-person code from your CSV", "SKY-4821")
    )

    /** Filled in from the clock at send time — no CSV column required. */
    val automatic: List<TemplateVariable> = listOf(
        auto("date", "Today's date", "5 Aug 2026"),
        auto("tmr_date", "Tomorrow's date", "6 Aug 2026"),
        auto("time_12", "Current time, 12-hour", "9:41 PM"),
        auto("time_24", "Current time, 24-hour", "21:41"),
        auto("day_of_week", "Day of the week", "Wednesday"),
        auto("tmr_day_of_week", "Tomorrow's day of the week", "Thursday"),
        auto("day_of_month", "Day of the month", "5"),
        auto("month", "Current month", "August"),
        auto("next_month", "Next month", "September"),
        auto("year", "Current year", "2026"),
        auto("random_number", "A random 4-digit number", "4821")
    )

    val all: List<TemplateVariable> get() = csv + automatic

    /** Default contents of the editor's "columns in your CSV" list. */
    val defaultKnownNames: List<String> = all.map { it.name }

    private val samplesByName: Map<String, String> = all.associate { it.name to it.sampleValue }

    /**
     * A preview value for [name]. Unrecognised names are the client's own CSV
     * columns, so they get an obvious `[column]` placeholder rather than an
     * invented value — the preview should never pretend to know data it doesn't.
     */
    fun sampleFor(name: String): String =
        samplesByName[name.trim().lowercase()] ?: "[${name.trim()}]"

    fun sampleValuesFor(names: Collection<String>): Map<String, String> =
        names.associate { it.trim().lowercase() to sampleFor(it) }

    /**
     * The real values for [automatic] at send time. [random] is injected so
     * `random_number` is testable; [locale] defaults to English rather than the
     * device locale so a preview on the client's phone matches what CI asserts.
     */
    fun automaticValues(
        now: LocalDateTime,
        locale: Locale = Locale.ENGLISH,
        random: () -> Double = Math::random
    ): Map<String, String> {
        val tomorrow = now.plusDays(1)
        return mapOf(
            "date" to format(DATE_PATTERN, now, locale),
            "tmr_date" to format(DATE_PATTERN, tomorrow, locale),
            "time_12" to format("h:mm a", now, locale),
            "time_24" to format("HH:mm", now, locale),
            "day_of_week" to format("EEEE", now, locale),
            "tmr_day_of_week" to format("EEEE", tomorrow, locale),
            "day_of_month" to now.dayOfMonth.toString(),
            "month" to format("MMMM", now, locale),
            "next_month" to format("MMMM", now.plusMonths(1), locale),
            "year" to now.year.toString(),
            "random_number" to (1000 + (random() * 9000).toInt().coerceIn(0, 8999)).toString()
        )
    }

    private fun format(pattern: String, at: LocalDateTime, locale: Locale): String =
        DateTimeFormatter.ofPattern(pattern, locale).format(at)

    private fun variable(name: String, description: String, sample: String) =
        TemplateVariable(name, description, sample, TemplateVariable.Source.Csv)

    private fun auto(name: String, description: String, sample: String) =
        TemplateVariable(name, description, sample, TemplateVariable.Source.Automatic)
}
