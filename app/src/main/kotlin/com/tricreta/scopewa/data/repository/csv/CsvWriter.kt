package com.tricreta.scopewa.data.repository.csv

/**
 * The one place that knows how to quote a CSV cell.
 *
 * Extracted from Phase 2's `ContactExporter` when Phase 6 needed the same
 * escaping for the activity log — a rendered WhatsApp message routinely
 * contains commas, quotes and newlines, so a second hand-rolled escaper would
 * have been a second chance to get it wrong.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object CsvWriter {

    /** Excel only reads Unicode correctly when the file opens with a BOM. */
    const val BOM = "\uFEFF"

    /** Spreadsheets on Windows still expect CRLF; everything else tolerates it. */
    const val CRLF = "\r\n"

    /**
     * Quotes a cell only when it has to be — a value containing a comma, a
     * quote or a line break. Embedded quotes are doubled, as RFC 4180 asks.
     */
    fun cell(value: String): String =
        if (value.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun row(values: List<String>): String = values.joinToString(",") { cell(it) }

    /** A complete file: BOM, header, rows, trailing newline. */
    fun document(header: List<String>, rows: List<List<String>>): String =
        BOM + (listOf(row(header)) + rows.map(::row)).joinToString(CRLF) + CRLF
}
