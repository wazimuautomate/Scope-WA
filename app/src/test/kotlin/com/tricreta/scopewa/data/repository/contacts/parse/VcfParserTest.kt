package com.tricreta.scopewa.data.repository.contacts.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VcfParserTest {

    @Test
    fun `reads name and number from a simple vcard`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Joy Wanjiru
            TEL;TYPE=CELL:+254712345678
            END:VCARD
            """.trimIndent()
        )

        assertEquals(1, contacts.size)
        assertEquals("Joy Wanjiru", contacts[0].name)
        assertEquals("+254712345678", contacts[0].rawNumber)
    }

    @Test
    fun `several cards in one file all come through`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            FN:One
            TEL:0712345678
            END:VCARD
            BEGIN:VCARD
            FN:Two
            TEL:0722000111
            END:VCARD
            """.trimIndent()
        )

        assertEquals(listOf("One", "Two"), contacts.map { it.name })
    }

    @Test
    fun `structured N is used when FN is missing`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            N:Otieno;Brian;;;
            TEL:0733111222
            END:VCARD
            """.trimIndent()
        )

        assertEquals("Brian Otieno", contacts[0].name)
    }

    @Test
    fun `a card with no TEL is skipped rather than imported blank`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            FN:No Number
            EMAIL:nobody@example.com
            END:VCARD
            """.trimIndent()
        )

        assertTrue(contacts.isEmpty())
    }

    @Test
    fun `every TEL on a card becomes a contact, not just the first`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            FN:Two Lines
            TEL;TYPE=CELL:0712345678
            TEL;TYPE=WORK:0722000111
            END:VCARD
            """.trimIndent()
        )

        assertEquals(listOf("0712345678", "0722000111"), contacts.map { it.rawNumber })
    }

    @Test
    fun `folded continuation lines are rejoined`() {
        // RFC 6350 folding breaks a long line and marks the continuation with a
        // leading space, which is not part of the value.
        val contacts = VcfParser.parse(
            "BEGIN:VCARD\r\nFN:Munyao Kilon\r\n zo Mutiso\r\nTEL:0712345678\r\nEND:VCARD\r\n"
        )

        assertEquals("Munyao Kilonzo Mutiso", contacts[0].name)
    }

    @Test
    fun `quoted-printable names decode to real UTF-8`() {
        // What Android's own contact export produces for "José".
        val contacts = VcfParser.parse(
            "BEGIN:VCARD\r\n" +
                "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Jos=C3=A9\r\n" +
                "TEL:0712345678\r\n" +
                "END:VCARD\r\n"
        )

        assertEquals("José", contacts[0].name)
    }

    @Test
    fun `apple style grouped properties are recognised`() {
        val contacts = VcfParser.parse(
            """
            BEGIN:VCARD
            FN:Grouped
            item1.TEL;type=CELL:0712345678
            END:VCARD
            """.trimIndent()
        )

        assertEquals("0712345678", contacts[0].rawNumber)
    }

    @Test
    fun `lowercase begin vcard still splits cards`() {
        val contacts = VcfParser.parse(
            "begin:vcard\nFN:Lower\nTEL:0712345678\nend:vcard\n"
        )

        assertEquals(1, contacts.size)
        assertEquals("Lower", contacts[0].name)
    }

    @Test
    fun `text with no cards yields nothing`() {
        assertTrue(VcfParser.parse("this is not a vcard").isEmpty())
    }
}
