package com.tricreta.scopewa.data.repository.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is where a WhatsApp layout change does its quietest damage — a
 * broken selector is obvious, a parser that starts reading "about" text as
 * phone numbers is not. These tests pin both directions: what must be read,
 * and what must never be.
 */
class MemberRowParserTest {

    private val parser = MemberRowParser(defaultCountryCode = "254")

    // ---- numbers that must be read ---------------------------------------

    @Test
    fun `an unsaved contact's row title is their number`() {
        val member = parser.parse(title = "+254 712 345 678")!!
        assertEquals("+254712345678", member.phoneE164)
        assertEquals(MemberNumberStatus.Visible, member.numberStatus)
        assertTrue(member.hasNumber)
    }

    @Test
    fun `a local number gets the default country code`() {
        val member = parser.parse(title = "0712345678")!!
        assertEquals("+254712345678", member.phoneE164)
    }

    @Test
    fun `number formatting with dashes and parens is handled`() {
        assertEquals("+254712345678", parser.parse(title = "+254-712-345-678")!!.phoneE164)
        assertEquals("+254712345678", parser.parse(title = "(254) 712 345 678")!!.phoneE164)
    }

    @Test
    fun `a push name in the subtitle becomes the display name`() {
        val member = parser.parse(title = "+254712345678", subtitle = "~Amina")!!
        assertEquals("+254712345678", member.phoneE164)
        assertEquals("Amina", member.displayName)
    }

    @Test
    fun `when only a number is available it doubles as the display name`() {
        val member = parser.parse(title = "+254712345678", subtitle = "Available")!!
        assertEquals("+254712345678", member.displayName)
    }

    // ---- rows with no readable number -------------------------------------

    @Test
    fun `a saved contact shows a name and yields no number`() {
        val member = parser.parse(title = "Brian Otieno", subtitle = "At the gym")!!
        assertNull(member.phoneE164)
        assertEquals(MemberNumberStatus.NotShown, member.numberStatus)
        assertEquals("Brian Otieno", member.displayName)
        assertFalse(member.hasNumber)
    }

    @Test
    fun `a tilde push name is cleaned up for display`() {
        val member = parser.parse(title = "~Johnny")!!
        assertEquals("Johnny", member.displayName)
        assertNull(member.phoneE164)
    }

    @Test
    fun `a title that is only a tilde still gets a non-blank name`() {
        // Stripping "~" from "~" leaves an empty string — must not hand back a
        // blank name just because the marker had nothing after it.
        val member = parser.parse(title = "~")!!
        assertTrue(member.displayName.isNotBlank())
    }

    @Test
    fun `every parsed member has a non-blank display name`() {
        val cases = listOf(
            parser.parse(title = "+254712345678"),
            parser.parse(title = "Brian Otieno", subtitle = "Available"),
            parser.parse(title = "~Johnny"),
            parser.parse(title = "~", subtitle = "Available")
        )
        cases.forEach { member ->
            assertTrue("every captured member must have a name to show", member!!.displayName.isNotBlank())
        }
    }

    @Test
    fun `a blank row is skipped entirely`() {
        assertNull(parser.parse(title = null))
        assertNull(parser.parse(title = "   "))
    }

    // ---- things that must NOT become phone numbers ------------------------

    @Test
    fun `an about text containing a number is not harvested as the member's number`() {
        // The row belongs to a saved contact; the digits are in their status.
        // Treating this as their number would message the wrong person.
        val member = parser.parse(title = "Brian Otieno", subtitle = "Call me on 0712345678 anytime")!!
        assertNull(member.phoneE164)
    }

    @Test
    fun `ordinary status text never yields a number`() {
        listOf("Available", "At work", "Busy", "Nairobi 2024", "Hey there! I am using WhatsApp")
            .forEach { subtitle ->
                val member = parser.parse(title = "Some Person", subtitle = subtitle)!!
                assertNull("'$subtitle' must not parse as a number", member.phoneE164)
            }
    }

    @Test
    fun `a too-short digit run is rejected`() {
        assertNull(parser.parse(title = "12345")!!.phoneE164)
    }

    // ---- admin and self ----------------------------------------------------

    @Test
    fun `an admin badge on the row is recognised`() {
        val member = parser.parse(title = "+254712345678", adminLabel = "Group admin")!!
        assertTrue(member.isAdmin)
    }

    @Test
    fun `admin wording in the subtitle is also recognised`() {
        assertTrue(parser.parse(title = "Brian", subtitle = "Group admin")!!.isAdmin)
        assertTrue(parser.parse(title = "Brian", subtitle = "Admin")!!.isAdmin)
    }

    @Test
    fun `admin matching is case-insensitive`() {
        assertTrue(parser.parse(title = "Brian", adminLabel = "GROUP ADMIN")!!.isAdmin)
    }

    @Test
    fun `a normal member is not marked admin`() {
        assertFalse(parser.parse(title = "+254712345678", subtitle = "Available")!!.isAdmin)
    }

    @Test
    fun `the You row is flagged as self`() {
        assertTrue(parser.parse(title = "You")!!.isSelf)
        assertFalse(parser.parse(title = "Brian")!!.isSelf)
    }

    // ---- raw text is retained for diagnosing layout changes ---------------

    @Test
    fun `raw title and subtitle are preserved`() {
        val member = parser.parse(title = "Brian Otieno", subtitle = "At the gym")!!
        assertEquals("Brian Otieno", member.rawTitle)
        assertEquals("At the gym", member.rawSubtitle)
    }
}
