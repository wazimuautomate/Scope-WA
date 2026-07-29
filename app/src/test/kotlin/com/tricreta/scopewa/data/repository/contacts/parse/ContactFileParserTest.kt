package com.tricreta.scopewa.data.repository.contacts.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFileParserTest {

    @Test
    fun `csv is returned unresolved so the user can pick columns`() {
        val parsed = ContactFileParser.parse("clients.csv", "name,phone\nJoy,0712345678\n")

        assertTrue(parsed is ParsedFile.Csv)
        assertEquals(listOf("name", "phone"), (parsed as ParsedFile.Csv).table.headers)
    }

    @Test
    fun `vcf and txt come back ready to import`() {
        val vcf = ContactFileParser.parse(
            "contacts.vcf",
            "BEGIN:VCARD\nFN:Joy\nTEL:0712345678\nEND:VCARD\n"
        )
        val txt = ContactFileParser.parse("numbers.txt", "0712345678\n")

        assertTrue(vcf is ParsedFile.Ready)
        assertTrue(txt is ParsedFile.Ready)
    }

    @Test
    fun `an unknown extension is reported rather than guessed at`() {
        val parsed = ContactFileParser.parse("contacts.xlsx", "anything")

        assertTrue(parsed is ParsedFile.Unsupported)
    }

    @Test
    fun `a vcf with no usable cards is unsupported, not an empty success`() {
        val parsed = ContactFileParser.parse("empty.vcf", "BEGIN:VCARD\nFN:No Number\nEND:VCARD\n")

        assertTrue(parsed is ParsedFile.Unsupported)
    }

    @Test
    fun `phone column is guessed from the header name`() {
        val table = CsvParser.parse("Full Name,Mobile Number,Town\nJoy,0712345678,Nakuru\n")

        assertEquals("Mobile Number", ContactFileParser.guessPhoneColumn(table))
        assertEquals("Full Name", ContactFileParser.guessNameColumn(table))
    }

    @Test
    fun `phone column falls back to whichever column looks most like numbers`() {
        val table = CsvParser.parse("a,b\nJoy,0712345678\nOtieno,0722000111\n")

        assertEquals("b", ContactFileParser.guessPhoneColumn(table))
        assertNull(ContactFileParser.guessNameColumn(table))
    }

    @Test
    fun `csv rows keep every other column as a template variable`() {
        val table = CsvParser.parse(
            "name,phone,town,last_bundle\nJoy,0712345678,Nakuru,20GB\n"
        )

        val contacts = ContactFileParser.contactsFromCsv(table, "phone", "name")

        assertEquals("0712345678", contacts[0].rawNumber)
        assertEquals("Joy", contacts[0].name)
        assertEquals("Nakuru", contacts[0].fields["town"])
        assertEquals("20GB", contacts[0].fields["last_bundle"])
        // The phone column stays available too — a template may want to echo it.
        assertEquals("0712345678", contacts[0].fields["phone"])
    }

    @Test
    fun `a csv with no name column still imports numbers`() {
        val table = CsvParser.parse("phone\n0712345678\n")

        val contacts = ContactFileParser.contactsFromCsv(table, "phone", nameColumn = null)

        assertEquals("0712345678", contacts[0].rawNumber)
        assertEquals("", contacts[0].name)
    }
}
