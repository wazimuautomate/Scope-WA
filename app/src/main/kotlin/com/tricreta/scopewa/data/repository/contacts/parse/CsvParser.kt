package com.tricreta.scopewa.data.repository.contacts.parse

/**
 * A parsed CSV file: the (uniquified) header row plus one map per data row.
 *
 * Every column survives into [rows] — not just phone and name — because
 * architecture doc section 6 layer 1 says *any* CSV column becomes a template
 * variable (`{town}`, `{last_bundle}`). Dropping columns here would quietly
 * remove that capability from Phase 5.
 */
data class CsvTable(
    val headers: List<String>,
    val rows: List<Map<String, String>>
)

/**
 * Dependency-free, RFC-4180-ish CSV parser. Kotlin port of
 * `docs/reference/whatsapp-group-adder/lib/csv.js`, which is already proven
 * against the client's real exports.
 *
 * Handles quoted fields, embedded commas/newlines, escaped quotes (`""`),
 * CRLF / LF / lone-CR line endings, and a leading UTF-8 BOM.
 *
 * Pure Kotlin on purpose — no Android imports — so it is unit tested in CI
 * without a phone.
 */
object CsvParser {

    fun parse(text: String): CsvTable {
        val src = text.removePrefix(BOM)

        val records = mutableListOf<List<String>>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            record.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            // Skip fully-empty lines rather than emitting a bogus one-blank-cell row.
            if (!(record.size == 1 && record[0].isEmpty())) records.add(record.toList())
            record.clear()
        }

        while (i < src.length) {
            val ch = src[i]

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(ch)
                    i++
                }
                continue
            }

            when (ch) {
                '"' -> {
                    inQuotes = true
                    i++
                }

                ',' -> {
                    endField()
                    i++
                }

                '\r' -> {
                    endRecord()
                    if (i + 1 < src.length && src[i + 1] == '\n') i++
                    i++
                }

                '\n' -> {
                    endRecord()
                    i++
                }

                else -> {
                    field.append(ch)
                    i++
                }
            }
        }

        // Flush a final record when the file doesn't end with a newline.
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()

        if (records.isEmpty()) return CsvTable(emptyList(), emptyList())

        val headers = uniqueHeaders(records[0].map { it.trim() })
        val rows = records.drop(1).map { cells ->
            headers.withIndex().associate { (idx, header) ->
                header to (cells.getOrNull(idx) ?: "").trim()
            }
        }
        return CsvTable(headers, rows)
    }

    /**
     * Row maps are keyed by header, so blank and duplicate headers would
     * silently swallow columns. Rename them instead: blank becomes
     * `column_<n>`, a repeat becomes `<name>_2`, `<name>_3`, …
     */
    internal fun uniqueHeaders(raw: List<String>): List<String> {
        val taken = mutableSetOf<String>()
        return raw.mapIndexed { index, header ->
            val base = header.ifBlank { "column_${index + 1}" }
            var candidate = base
            var suffix = 1
            while (!taken.add(candidate.lowercase())) {
                suffix++
                candidate = "${base}_$suffix"
            }
            candidate
        }
    }

    private const val BOM = "\uFEFF"
}
