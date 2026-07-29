package com.tricreta.scopewa.data.repository.contacts

import com.tricreta.scopewa.data.repository.contacts.parse.ParsedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactImporterTest {

    private val importer = ContactImporter()

    @Test
    fun `numbers are normalised to E164 on the way in`() {
        val plan = importer.plan(
            listOf(
                ParsedContact("0712345678", "Joy"),
                ParsedContact("+254722000111", "Otieno"),
                ParsedContact("733 111 222", "Mwangi")
            )
        )

        assertEquals(
            listOf("+254712345678", "+254722000111", "+254733111222"),
            plan.newContacts.map { it.phoneE164 }
        )
    }

    @Test
    fun `the same person written three ways is one contact`() {
        val plan = importer.plan(
            listOf(
                ParsedContact("0712345678", "Joy"),
                ParsedContact("+254712345678", ""),
                ParsedContact("254-712-345-678", "")
            )
        )

        assertEquals(1, plan.newContacts.size)
        assertEquals(2, plan.duplicatesInFile)
    }

    @Test
    fun `a duplicate fills in a name the first row was missing`() {
        val plan = importer.plan(
            listOf(
                ParsedContact("0712345678", ""),
                ParsedContact("0712345678", "Joy")
            )
        )

        assertEquals("Joy", plan.newContacts.single().name)
    }

    @Test
    fun `the first row's own values win over a later duplicate's`() {
        val plan = importer.plan(
            listOf(
                ParsedContact("0712345678", "Joy", mapOf("town" to "Nakuru")),
                ParsedContact("0712345678", "Joyce", mapOf("town" to "Nairobi", "tier" to "gold"))
            )
        )

        val merged = plan.newContacts.single()
        assertEquals("Joy", merged.name)
        assertEquals("Nakuru", merged.fields["town"])
        // …but a field only the later row had is still picked up.
        assertEquals("gold", merged.fields["tier"])
    }

    @Test
    fun `rows already in the database are separated from new ones`() {
        val plan = importer.plan(
            rows = listOf(
                ParsedContact("0712345678", "Joy"),
                ParsedContact("0722000111", "Otieno")
            ),
            existingNumbers = setOf("+254712345678")
        )

        assertEquals(listOf("+254722000111"), plan.newContacts.map { it.phoneE164 })
        assertEquals(listOf("+254712345678"), plan.existingContacts.map { it.phoneE164 })
        assertEquals(2, plan.importable.size)
    }

    @Test
    fun `suppressed numbers are never importable`() {
        val plan = importer.plan(
            rows = listOf(
                ParsedContact("0712345678", "Said STOP"),
                ParsedContact("0722000111", "Fine")
            ),
            suppressedNumbers = setOf("+254712345678")
        )

        assertEquals(listOf("+254712345678"), plan.suppressed.map { it.phoneE164 })
        assertEquals(listOf("+254722000111"), plan.importable.map { it.phoneE164 })
    }

    @Test
    fun `suppression beats an existing contact row`() {
        val plan = importer.plan(
            rows = listOf(ParsedContact("0712345678", "Joy")),
            existingNumbers = setOf("+254712345678"),
            suppressedNumbers = setOf("+254712345678")
        )

        assertEquals(1, plan.suppressed.size)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `unusable rows are kept with a reason instead of being silently dropped`() {
        val plan = importer.plan(
            listOf(
                ParsedContact("", "No number"),
                ParsedContact("not a number at all", "Letters"),
                ParsedContact("123", "Too short")
            )
        )

        assertEquals(
            listOf(RejectReason.Blank, RejectReason.NotANumber, RejectReason.BadLength),
            plan.rejected.map { it.reason }
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `every row read is accounted for exactly once`() {
        val plan = importer.plan(
            rows = listOf(
                ParsedContact("0712345678", "New"),
                ParsedContact("0712345678", "Duplicate"),
                ParsedContact("0722000111", "Known"),
                ParsedContact("0733111222", "Blocked"),
                ParsedContact("nope", "Rejected")
            ),
            existingNumbers = setOf("+254722000111"),
            suppressedNumbers = setOf("+254733111222")
        )

        assertEquals(5, plan.rowsRead)
        assertEquals(1, plan.newContacts.size)
        assertEquals(1, plan.existingContacts.size)
        assertEquals(1, plan.suppressed.size)
        assertEquals(1, plan.rejected.size)
        assertEquals(1, plan.duplicatesInFile)
    }

    @Test
    fun `an empty file plans nothing`() {
        val plan = importer.plan(emptyList())

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.rowsRead)
    }
}
