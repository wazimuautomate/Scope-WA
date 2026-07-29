package com.tricreta.scopewa.data.db

/**
 * Encodes a `List<String>` / `Map<String, String>` into a single text column.
 *
 * Hand-rolled rather than JSON because the alternatives both cost something we
 * don't want: `org.json` is Android-only (so the encoding stops being unit
 * testable in CI), and kotlinx.serialization means another compiler plugin for
 * two columns. This is ~40 lines and fully tested.
 *
 * Separators are ASCII unit/record separators, which never occur in real
 * contact data — but they're escaped anyway, along with the escape character
 * itself, so a pathological CSV can't corrupt a row.
 *
 * Pure Kotlin — no Android imports.
 */
internal object StringCodec {

    fun encodeList(values: List<String>): String =
        values.joinToString(ITEM.toString()) { escape(it) }

    fun decodeList(encoded: String?): List<String> =
        if (encoded.isNullOrEmpty()) emptyList()
        else encoded.split(ITEM).map { unescape(it) }

    fun encodeMap(values: Map<String, String>): String =
        values.entries.joinToString(ITEM.toString()) { (key, value) ->
            escape(key) + PAIR + escape(value)
        }

    fun decodeMap(encoded: String?): Map<String, String> {
        if (encoded.isNullOrEmpty()) return emptyMap()
        return encoded.split(ITEM).mapNotNull { entry ->
            val separator = entry.indexOf(PAIR)
            if (separator < 0) null
            else unescape(entry.substring(0, separator)) to unescape(entry.substring(separator + 1))
        }.toMap()
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                ESCAPE -> append(ESCAPE).append(ESCAPE)
                ITEM -> append(ESCAPE).append('i')
                PAIR -> append(ESCAPE).append('p')
                else -> append(ch)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == ESCAPE && i + 1 < value.length) {
                when (value[i + 1]) {
                    ESCAPE -> append(ESCAPE)
                    'i' -> append(ITEM)
                    'p' -> append(PAIR)
                    else -> append(value[i + 1])
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    private const val ITEM = '\u001F'   // ASCII unit separator — between entries
    private const val PAIR = '\u001E'   // ASCII record separator — between key and value
    private const val ESCAPE = '\\'
}
