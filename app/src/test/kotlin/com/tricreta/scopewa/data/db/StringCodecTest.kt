package com.tricreta.scopewa.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringCodecTest {

    @Test
    fun `a list round-trips`() {
        val values = listOf("vip", "nakuru", "data challenge")

        assertEquals(values, StringCodec.decodeList(StringCodec.encodeList(values)))
    }

    @Test
    fun `a map round-trips`() {
        val values = mapOf("town" to "Nakuru", "last_bundle" to "20GB")

        assertEquals(values, StringCodec.decodeMap(StringCodec.encodeMap(values)))
    }

    @Test
    fun `empty and null decode to empty, not to a one-blank-entry collection`() {
        assertTrue(StringCodec.decodeList("").isEmpty())
        assertTrue(StringCodec.decodeList(null).isEmpty())
        assertTrue(StringCodec.decodeMap("").isEmpty())
        assertTrue(StringCodec.decodeMap(null).isEmpty())
        assertEquals("", StringCodec.encodeList(emptyList()))
        assertEquals("", StringCodec.encodeMap(emptyMap()))
    }

    @Test
    fun `blank values survive`() {
        assertEquals(listOf("", "b", ""), StringCodec.decodeList(StringCodec.encodeList(listOf("", "b", ""))))
        assertEquals(
            mapOf("a" to "", "" to "b"),
            StringCodec.decodeMap(StringCodec.encodeMap(mapOf("a" to "", "" to "b")))
        )
    }

    @Test
    fun `values containing the separators cannot corrupt neighbouring entries`() {
        val nasty = listOf("a\u001Fb", "c\u001Ed", "e\\f", "\\\\")

        assertEquals(nasty, StringCodec.decodeList(StringCodec.encodeList(nasty)))
    }

    @Test
    fun `keys and values containing separators survive too`() {
        val nasty = mapOf("k\u001E1" to "v\u001F1", "back\\slash" to "value")

        assertEquals(nasty, StringCodec.decodeMap(StringCodec.encodeMap(nasty)))
    }

    @Test
    fun `real-world contact data with commas, quotes and emoji survives`() {
        val values = mapOf(
            "note" to "Wanjiku, \"Mary\" — paid ✅",
            "town" to "Nairobi"
        )

        assertEquals(values, StringCodec.decodeMap(StringCodec.encodeMap(values)))
    }

    @Test
    fun `the room converters use the same encoding`() {
        val converters = Converters()
        val tags = listOf("vip", "waitlist")
        val fields = mapOf("town" to "Nakuru")

        assertEquals(tags, converters.dbToStringList(converters.stringListToDb(tags)))
        assertEquals(fields, converters.dbToStringMap(converters.stringMapToDb(fields)))
        assertTrue(converters.dbToStringList(converters.stringListToDb(null)).isEmpty())
        assertTrue(converters.dbToStringMap(converters.stringMapToDb(null)).isEmpty())
    }
}
