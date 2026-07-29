package com.tricreta.scopewa.data.repository.contacts

/**
 * A contact flattened for export. Deliberately decoupled from the Room entity
 * so Phase 4's extraction results (which have no row in `contacts` yet) and
 * Phase 6's reports can reuse this exporter without dragging the database in.
 *
 * [hidden] carries WhatsApp's LID rollout honestly — see architecture doc
 * section 3.2 point 2. A hidden member exports as the literal text `hidden`
 * rather than being silently dropped, so counts stay truthful.
 */
data class ExportRecord(
    val name: String = "",
    val phoneE164: String = "",
    val hidden: Boolean = false,
    val saved: Boolean = false,
    val optedOut: Boolean = false,
    val tags: List<String> = emptyList(),
    val sourceGroup: String? = null,
    val lists: List<String> = emptyList()
)

/** WhatsApp hides some group members' numbers (LID) — say so, don't drop them. */
private const val HIDDEN_NUMBER = "hidden"

/** Separates repeated values inside a single cell, as the reference extension does. */
private const val SUB_SEPARATOR = " | "

private fun yesNo(value: Boolean) = if (value) "yes" else "no"

/** One exportable column. Port of the `FIELDS` map in the reference extension. */
enum class ExportField(val key: String, val label: String, val read: (ExportRecord) -> String) {
    Name("name", "Name", { it.name }),
    Number("number", "Number", { if (it.hidden) HIDDEN_NUMBER else it.phoneE164 }),
    Saved("saved", "Saved Contact", { yesNo(it.saved) }),
    OptedOut("opted_out", "Opted Out", { yesNo(it.optedOut) }),
    Tags("tags", "Tags", { it.tags.joinToString(SUB_SEPARATOR) }),
    SourceGroup("source_group", "Source Group", { it.sourceGroup.orEmpty() }),
    Lists("lists", "Lists", { it.lists.joinToString(SUB_SEPARATOR) });

    companion object {
        /** Just enough to re-import somewhere else. */
        val Slim = listOf(Name, Number)

        /** Everything Scope WA knows about a contact. */
        val All = entries.toList()
    }
}

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    Csv("csv", "text/csv", "CSV — opens in Excel or Sheets"),
    Txt("txt", "text/plain", "TXT — one number per line"),
    Vcf("vcf", "text/vcard", "VCF — a vCard file"),
    Json("json", "application/json", "JSON — for other tools")
}

/**
 * Turns contacts into file content. Kotlin port of
 * `docs/reference/whatsapp-contact-extractor/lib/exporters.js`.
 *
 * > **Export means a file, and only a file.** Per the client's answer in
 * > architecture doc section 10 Q8, nothing here — least of all [toVcf] — may
 * > ever be routed into Android's contacts provider. A `.vcf` produced here is
 * > a document the user saves and moves around themselves.
 *
 * XLSX is intentionally not implemented yet: it needs a ZIP writer and belongs
 * with Phase 4's extraction export, where it was actually asked for.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object ContactExporter {

    fun export(
        records: List<ExportRecord>,
        format: ExportFormat,
        fields: List<ExportField> = ExportField.Slim
    ): String = when (format) {
        ExportFormat.Csv -> toCsv(records, fields)
        ExportFormat.Txt -> toTxt(records)
        ExportFormat.Vcf -> toVcf(records)
        ExportFormat.Json -> toJson(records)
    }

    /** Leading BOM and CRLF endings so Excel opens Unicode names correctly. */
    fun toCsv(records: List<ExportRecord>, fields: List<ExportField> = ExportField.Slim): String {
        val header = fields.joinToString(",") { csvCell(it.label) }
        val lines = records.map { record -> fields.joinToString(",") { csvCell(it.read(record)) } }
        return BOM + (listOf(header) + lines).joinToString(CRLF) + CRLF
    }

    /** Numbers only, one per line. Hidden members have nothing to write. */
    fun toTxt(records: List<ExportRecord>): String =
        records.filter { !it.hidden && it.phoneE164.isNotBlank() }
            .joinToString(CRLF) { it.phoneE164 }
            .let { if (it.isEmpty()) "" else it + CRLF }

    /** vCard 3.0. A card without a real number would be useless, so those are skipped. */
    fun toVcf(records: List<ExportRecord>): String =
        records.filter { !it.hidden && it.phoneE164.isNotBlank() }
            .joinToString(CRLF) { record ->
                val name = record.name.ifBlank { record.phoneE164 }
                buildList {
                    add("BEGIN:VCARD")
                    add("VERSION:3.0")
                    add("FN:${vcardEscape(name)}")
                    add("N:${vcardEscape(name)};;;;")
                    add("TEL;TYPE=CELL:${record.phoneE164}")
                    record.sourceGroup?.takeIf { it.isNotBlank() }?.let {
                        add("NOTE:${vcardEscape("WhatsApp group: $it")}")
                    }
                    add("END:VCARD")
                }.joinToString(CRLF)
            }
            .let { if (it.isEmpty()) "" else it + CRLF }

    /** Hand-rolled so this stays pure Kotlin — `org.json` is Android-only. */
    fun toJson(records: List<ExportRecord>): String {
        if (records.isEmpty()) return "[]\n"
        return records.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { record ->
            listOf(
                "\"name\": ${jsonString(record.name)}",
                "\"number\": ${if (record.hidden) "null" else jsonString(record.phoneE164)}",
                "\"hidden\": ${record.hidden}",
                "\"savedContact\": ${record.saved}",
                "\"optedOut\": ${record.optedOut}",
                "\"tags\": ${jsonArray(record.tags)}",
                "\"sourceGroup\": ${record.sourceGroup?.let(::jsonString) ?: "null"}",
                "\"lists\": ${jsonArray(record.lists)}"
            ).joinToString(",\n    ", prefix = "  {\n    ", postfix = "\n  }")
        }
    }

    /** `contacts-2026-07-29` style base name; the caller appends the extension. */
    fun fileNameFor(label: String, format: ExportFormat): String {
        val slug = label.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .ifBlank { "contacts" }
        return "$slug.${format.extension}"
    }

    private fun csvCell(value: String): String =
        if (value.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun vcardEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    private fun jsonArray(values: List<String>): String =
        values.joinToString(", ", prefix = "[", postfix = "]", transform = ::jsonString)

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private const val CRLF = "\r\n"
    private const val BOM = "\uFEFF"
}
