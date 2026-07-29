package com.tricreta.scopewa.data.repository.contacts.parse

/**
 * vCard (`.vcf`) parser. Kotlin port of `parseVCF` in
 * `docs/reference/whatsapp-group-adder/lib/parse.js`, with three additions the
 * JS version doesn't need but an Android phone does:
 *
 *  1. **Quoted-printable decoding.** Android's own "export contacts" produces
 *     `N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:...` for any name with an
 *     accent or non-Latin character. Without this those names import as
 *     mojibake.
 *  2. **Every `TEL` line, not just the first.** A card with a mobile and a
 *     second mobile would otherwise lose one. Junk landlines get filtered out
 *     later by [com.tricreta.scopewa.brain.phone.PhoneNormalizer] validity and
 *     dedupe, so keeping them is the safer default.
 *  3. **Apple-style grouped properties** (`item1.TEL:…`).
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object VcfParser {

    fun parse(text: String): List<ParsedContact> {
        var src = text.removePrefix("\uFEFF")

        // Quoted-printable soft line breaks: a line ending in "=" continues on
        // the next one. Only join when the file actually uses QP, so a stray
        // trailing "=" in a plain vCard isn't swallowed.
        if (src.contains(QUOTED_PRINTABLE, ignoreCase = true)) {
            src = src.replace("=\r\n", "").replace("=\n", "")
        }

        // Unfold RFC-6350 folded lines: a leading space/tab continues the previous line.
        val unfolded = src.replace("\r\n ", "").replace("\r\n\t", "")
            .replace("\n ", "").replace("\n\t", "")

        return unfolded.split(BEGIN_VCARD)
            .drop(1) // everything before the first BEGIN:VCARD is not a card
            .mapNotNull(::parseCard)
            .flatten()
    }

    private fun parseCard(card: String): List<ParsedContact>? {
        var formattedName = ""
        var structuredName = ""
        val telephones = LinkedHashSet<String>()
        val fields = mutableMapOf<String, String>()

        for (rawLine in card.split("\r\n", "\r", "\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val colon = line.indexOf(':')
            if (colon <= 0) continue

            val property = Property.of(line.substring(0, colon))
            val value = decode(line.substring(colon + 1), property)

            when (property.name) {
                "FN" -> if (formattedName.isEmpty()) formattedName = value.trim()
                "N" -> if (structuredName.isEmpty()) structuredName = joinStructuredName(value)
                "TEL" -> if (value.isNotBlank()) telephones.add(value.trim())
                "ORG", "TITLE", "EMAIL", "NOTE" ->
                    if (value.isNotBlank()) fields.putIfAbsent(property.name.lowercase(), value.trim())
            }
        }

        if (telephones.isEmpty()) return null

        val name = formattedName.ifBlank { structuredName }
        return telephones.map { ParsedContact(rawNumber = it, name = name, fields = fields.toMap()) }
    }

    /** `N:Last;First;Middle;Prefix;Suffix` → `"First Last"`. */
    private fun joinStructuredName(value: String): String {
        val parts = value.split(';')
        val family = parts.getOrNull(0)?.trim().orEmpty()
        val given = parts.getOrNull(1)?.trim().orEmpty()
        return listOf(given, family).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun decode(value: String, property: Property): String =
        if (property.isQuotedPrintable) decodeQuotedPrintable(value) else value

    /**
     * Decodes `=C3=A9` style escapes into UTF-8 text. Bytes are collected first
     * and decoded once at the end, because a single character can span several
     * `=XX` escapes.
     */
    internal fun decodeQuotedPrintable(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '=' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes.add(decoded.toByte())
                    i += 3
                    continue
                }
            }
            // Non-escape characters are ASCII by definition of quoted-printable.
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    /** The bit of a vCard line before the colon: `item1.TEL;TYPE=CELL`. */
    private data class Property(val name: String, val isQuotedPrintable: Boolean) {
        companion object {
            fun of(head: String): Property {
                val segments = head.split(';')
                val name = segments.first()
                    .substringAfterLast('.') // drop Apple's `item1.` grouping
                    .trim()
                    .uppercase()
                val quotedPrintable = segments.drop(1)
                    .any { it.contains(QUOTED_PRINTABLE, ignoreCase = true) }
                return Property(name, quotedPrintable)
            }
        }
    }

    /** Card delimiter. Case-insensitive because lowercase `begin:vcard` is legal. */
    private val BEGIN_VCARD = Regex("BEGIN:VCARD", RegexOption.IGNORE_CASE)

    private const val QUOTED_PRINTABLE = "QUOTED-PRINTABLE"
}
