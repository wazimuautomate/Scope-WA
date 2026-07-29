package com.tricreta.scopewa.data.repository.contacts.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtParserTest {

    @Test
    fun `one bare number per line`() {
        val contacts = TxtParser.parse("0712345678\n0722000111\n")

        assertEquals(listOf("0712345678", "0722000111"), contacts.map { it.rawNumber })
        assertTrue(contacts.all { it.name.isEmpty() })
    }

    @Test
    fun `name then number`() {
        val contacts = TxtParser.parse("Joy Wanjiru,0712345678\n")

        assertEquals("Joy Wanjiru", contacts[0].name)
        assertEquals("0712345678", contacts[0].rawNumber)
    }

    @Test
    fun `number then name works too - whichever token looks like a phone wins`() {
        val contacts = TxtParser.parse("0712345678,Joy Wanjiru\n")

        assertEquals("Joy Wanjiru", contacts[0].name)
        assertEquals("0712345678", contacts[0].rawNumber)
    }

    @Test
    fun `tabs and semicolons separate just as well as commas`() {
        val contacts = TxtParser.parse("Joy\t0712345678\nOtieno;0722000111\n")

        assertEquals(listOf("Joy", "Otieno"), contacts.map { it.name })
        assertEquals(listOf("0712345678", "0722000111"), contacts.map { it.rawNumber })
    }

    @Test
    fun `blank lines and hash comments are skipped`() {
        val contacts = TxtParser.parse("# my list\n\n0712345678\n\n# end\n")

        assertEquals(1, contacts.size)
    }

    @Test
    fun `extra tokens all become part of the name`() {
        val contacts = TxtParser.parse("Joy,Wanjiru,0712345678\n")

        assertEquals("Joy Wanjiru", contacts[0].name)
        assertEquals("0712345678", contacts[0].rawNumber)
    }

    @Test
    fun `crlf endings are handled`() {
        val contacts = TxtParser.parse("Joy,0712345678\r\nOtieno,0722000111\r\n")

        assertEquals(2, contacts.size)
    }

    @Test
    fun `with no phone-shaped token the last one is taken as the number`() {
        val contacts = TxtParser.parse("Joy,abc\n")

        assertEquals("abc", contacts[0].rawNumber)
        assertEquals("Joy", contacts[0].name)
    }
}
