package com.tricreta.scopewa.data.repository.contacts.parse

/**
 * What came out of a file the user picked.
 *
 * CSV is deliberately *not* resolved to contacts here: the user has to pick
 * which column holds the phone number first (the reference extension does the
 * same, and for the same reason — guessing wrong silently imports garbage).
 */
sealed interface ParsedFile {
    /** Needs a column choice before it becomes contacts. */
    data class Csv(val table: CsvTable) : ParsedFile

    /** VCF/TXT carry their own structure — ready to import. */
    data class Ready(val contacts: List<ParsedContact>) : ParsedFile

    /** Unrecognised extension, or a file with nothing usable in it. */
    data class Unsupported(val reason: String) : ParsedFile
}

/**
 * Picks a parser by file extension and, for CSV, guesses sensible default
 * columns. Kotlin port of `parseFile` / `contactsFromRows` in
 * `docs/reference/whatsapp-group-adder/lib/parse.js`.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object ContactFileParser {

    val SUPPORTED_EXTENSIONS = listOf("csv", "vcf", "vcard", "txt")

    /**
     * MIME types to hand Android's file picker. `application/octet-stream` is
     * in the list because plenty of file managers report a CSV as exactly that,
     * and leaving it out makes the user's own file un-pickable.
     */
    val PICKER_MIME_TYPES = arrayOf(
        "text/csv",
        "text/comma-separated-values",
        "text/plain",
        "text/vcard",
        "text/x-vcard",
        "application/octet-stream"
    )

    fun parse(fileName: String, text: String): ParsedFile {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "csv" -> {
                val table = CsvParser.parse(text)
                if (table.headers.isEmpty()) {
                    ParsedFile.Unsupported("That CSV has no header row.")
                } else {
                    ParsedFile.Csv(table)
                }
            }

            "vcf", "vcard" -> ready(VcfParser.parse(text), "No contacts with a phone number in that .vcf.")
            "txt" -> ready(TxtParser.parse(text), "No usable lines in that .txt.")
            else -> ParsedFile.Unsupported(
                "Unsupported file type. Import ${SUPPORTED_EXTENSIONS.joinToString(", ") { ".$it" }}."
            )
        }
    }

    private fun ready(contacts: List<ParsedContact>, emptyReason: String): ParsedFile =
        if (contacts.isEmpty()) ParsedFile.Unsupported(emptyReason) else ParsedFile.Ready(contacts)

    /**
     * Turns chosen columns into contacts. Every column other than the name
     * column is kept in [ParsedContact.fields] — including the phone column,
     * which templates may legitimately want to echo back.
     */
    fun contactsFromCsv(
        table: CsvTable,
        phoneColumn: String,
        nameColumn: String? = null
    ): List<ParsedContact> = table.rows.map { row ->
        ParsedContact(
            rawNumber = row[phoneColumn].orEmpty(),
            name = nameColumn?.let { row[it] }.orEmpty(),
            fields = row.filterKeys { it != nameColumn }
        )
    }

    /**
     * Best guess at the phone column: a header that reads like one, otherwise
     * whichever column has the most phone-shaped values. Returns null only for
     * a table with no columns at all — the user can always override.
     */
    fun guessPhoneColumn(table: CsvTable): String? {
        table.headers.firstOrNull { PHONE_HEADER.containsMatchIn(it) }?.let { return it }
        return table.headers.maxByOrNull { header ->
            table.rows.count { row -> (row[header]?.count(Char::isDigit) ?: 0) >= MIN_PHONE_DIGITS }
        }
    }

    /** Best guess at the name column; null when nothing looks like one. */
    fun guessNameColumn(table: CsvTable): String? =
        table.headers.firstOrNull { NAME_HEADER.containsMatchIn(it) }

    private val PHONE_HEADER = Regex("phone|number|mobile|msisdn|\\btel\\b|cell|whatsapp", RegexOption.IGNORE_CASE)
    private val NAME_HEADER = Regex("name", RegexOption.IGNORE_CASE)
    private const val MIN_PHONE_DIGITS = 6
}
