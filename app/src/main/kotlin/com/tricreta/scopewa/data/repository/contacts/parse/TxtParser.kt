package com.tricreta.scopewa.data.repository.contacts.parse

/**
 * Plain-text contact list parser. Kotlin port of `parseTXT` in
 * `docs/reference/whatsapp-group-adder/lib/parse.js`.
 *
 * One entry per line, in whichever of these shapes the file happens to use:
 * `number`, `number,name`, `name,number`, `name<TAB>number`, `name;number`.
 * Blank lines and `#` comments are skipped. Whichever token looks most like a
 * phone number wins; everything else on the line becomes the name.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object TxtParser {

    fun parse(text: String): List<ParsedContact> {
        val src = text.removePrefix("\uFEFF")
        val out = mutableListOf<ParsedContact>()

        for (rawLine in src.split("\r\n", "\r", "\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val tokens = line.split(',', ';', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue

            if (tokens.size == 1) {
                out.add(ParsedContact(rawNumber = tokens[0]))
                continue
            }

            val phoneIndex = tokens.indexOfFirst { it.count(Char::isDigit) >= MIN_PHONE_DIGITS }
                .takeIf { it >= 0 }
                ?: tokens.lastIndex
            val name = tokens.filterIndexed { index, _ -> index != phoneIndex }
                .joinToString(" ")
                .trim()

            out.add(ParsedContact(rawNumber = tokens[phoneIndex], name = name))
        }

        return out
    }

    /** Short enough to catch `712345678`, long enough not to match a house number. */
    private const val MIN_PHONE_DIGITS = 6
}
