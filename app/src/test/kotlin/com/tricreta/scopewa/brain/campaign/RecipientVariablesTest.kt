package com.tricreta.scopewa.brain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientVariablesTest {

    private fun recipient(
        contactId: Long = 1L,
        phone: String = "+254712345678",
        name: String = "Joy Wanjiru Mwangi",
        fields: Map<String, String> = emptyMap()
    ) = RecipientCandidate(
        contactId = contactId,
        phoneE164 = phone,
        displayName = name,
        fields = fields
    )

    @Test
    fun `identity values are derived from the contact itself`() {
        val variables = RecipientVariables.forRecipient(recipient())

        assertEquals("Joy Wanjiru Mwangi", variables["name"])
        assertEquals("Joy", variables["first_name"])
        assertEquals("Mwangi", variables["last_name"])
        assertEquals("+254712345678", variables["phone"])
    }

    @Test
    fun `a stale name column in the CSV never gets to address someone by the wrong name`() {
        // This is the whole reason identity values are written last: a column
        // left over from an older export must not rename a real person.
        val variables = RecipientVariables.forRecipient(
            recipient(
                fields = mapOf(
                    "name" to "Wrong Person",
                    "first_name" to "Wrong",
                    "last_name" to "Person",
                    "phone" to "+254799999999"
                )
            )
        )

        assertEquals("Joy Wanjiru Mwangi", variables["name"])
        assertEquals("Joy", variables["first_name"])
        assertEquals("Mwangi", variables["last_name"])
        assertEquals("+254712345678", variables["phone"])
    }

    @Test
    fun `an identity column also beats an automatic value of the same name`() {
        val variables = RecipientVariables.forRecipient(
            recipient(),
            automaticValues = mapOf("name" to "Automatic Name")
        )

        assertEquals("Joy Wanjiru Mwangi", variables["name"])
    }

    @Test
    fun `a CSV column the client named beats a built-in that happens to share its name`() {
        val variables = RecipientVariables.forRecipient(
            recipient(fields = mapOf("town" to "Nakuru")),
            automaticValues = mapOf("town" to "Nairobi")
        )

        assertEquals("Nakuru", variables["town"])
    }

    @Test
    fun `an automatic value survives when no column shadows it`() {
        val variables = RecipientVariables.forRecipient(
            recipient(fields = mapOf("town" to "Nakuru")),
            automaticValues = mapOf("date" to "29 Jul", "random_number" to "7")
        )

        assertEquals("29 Jul", variables["date"])
        assertEquals("7", variables["random_number"])
    }

    @Test
    fun `keys are lowercased and trimmed whichever source they came from`() {
        val variables = RecipientVariables.forRecipient(
            recipient(fields = mapOf("  Town  " to "Nakuru", "TIER" to "gold")),
            automaticValues = mapOf(" Date " to "29 Jul")
        )

        assertEquals("Nakuru", variables["town"])
        assertEquals("gold", variables["tier"])
        assertEquals("29 Jul", variables["date"])
    }

    @Test
    fun `a display name with stray whitespace is trimmed before it is used`() {
        val variables = RecipientVariables.forRecipient(recipient(name = "  Joy Mwangi  "))

        assertEquals("Joy Mwangi", variables["name"])
        assertEquals("Joy", variables["first_name"])
        assertEquals("Mwangi", variables["last_name"])
    }

    @Test
    fun `a contact with no name at all still produces every identity variable`() {
        val variables = RecipientVariables.forRecipient(recipient(name = "   "))

        assertEquals("", variables["name"])
        assertEquals("", variables["first_name"])
        assertEquals("", variables["last_name"])
        assertEquals("+254712345678", variables["phone"])
    }

    @Test
    fun `firstNameOf takes the first word whatever shape the name is`() {
        assertEquals("Joy", RecipientVariables.firstNameOf("Joy"))
        assertEquals("Joy", RecipientVariables.firstNameOf("Joy Mwangi"))
        assertEquals("Joy", RecipientVariables.firstNameOf("Joy Wanjiru Mwangi"))
    }

    @Test
    fun `firstNameOf a blank name stays blank so a template fallback can do its job`() {
        assertEquals("", RecipientVariables.firstNameOf(""))
        assertEquals("", RecipientVariables.firstNameOf("   "))
    }

    @Test
    fun `lastNameOf takes the last word, and is empty for a single-word name`() {
        assertEquals("", RecipientVariables.lastNameOf("Joy"))
        assertEquals("Mwangi", RecipientVariables.lastNameOf("Joy Mwangi"))
        assertEquals("Mwangi", RecipientVariables.lastNameOf("Joy Wanjiru Mwangi"))
    }

    @Test
    fun `lastNameOf a blank name is empty`() {
        assertEquals("", RecipientVariables.lastNameOf(""))
        assertEquals("", RecipientVariables.lastNameOf("   "))
    }

    @Test
    fun `knownNames always includes the identity names`() {
        assertEquals(
            setOf("name", "first_name", "last_name", "phone"),
            RecipientVariables.knownNames(emptyList())
        )
    }

    @Test
    fun `knownNames includes the automatic names, normalised`() {
        val names = RecipientVariables.knownNames(emptyList(), automaticNames = listOf(" Date ", "Random_Number"))

        assertTrue(names.containsAll(listOf("date", "random_number")))
    }

    @Test
    fun `knownNames covers columns from every recipient, not just the first`() {
        val names = RecipientVariables.knownNames(
            listOf(
                recipient(contactId = 1L, phone = "+254700000001", fields = mapOf("town" to "Nakuru")),
                recipient(contactId = 2L, phone = "+254700000002", fields = mapOf("Tier" to "gold")),
                recipient(contactId = 3L, phone = "+254700000003", fields = mapOf(" Balance " to "500"))
            ),
            automaticNames = listOf("date")
        )

        assertEquals(
            setOf("name", "first_name", "last_name", "phone", "date", "town", "tier", "balance"),
            names
        )
    }
}
