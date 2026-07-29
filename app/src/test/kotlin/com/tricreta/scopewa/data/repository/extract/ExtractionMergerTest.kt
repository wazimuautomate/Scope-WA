package com.tricreta.scopewa.data.repository.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionMergerTest {

    private fun member(number: String?, name: String = "Someone", isAdmin: Boolean = false) =
        ExtractedMember(
            rawTitle = number ?: name,
            displayName = name,
            phoneE164 = number,
            numberStatus = if (number != null) MemberNumberStatus.Visible else MemberNumberStatus.NotShown,
            isAdmin = isAdmin
        )

    private fun group(name: String, vararg members: ExtractedMember) =
        GroupExtraction(groupName = name, members = members.toList())

    @Test
    fun `the same number in two groups collapses to one row`() {
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001", "Amina")),
                group("Waitlist", member("+254700000001", "Amina"))
            )
        )

        assertEquals(1, merged.members.size)
        assertEquals(1, merged.duplicatesCollapsed)
        assertEquals(listOf("Fifth", "Waitlist"), merged.members.first().sourceGroups)
        assertTrue(merged.members.first().isInMultipleGroups)
    }

    @Test
    fun `distinct numbers are all kept`() {
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001"), member("+254700000002")),
                group("Waitlist", member("+254700000003"))
            )
        )

        assertEquals(3, merged.members.size)
        assertEquals(0, merged.duplicatesCollapsed)
    }

    @Test
    fun `admin in any group marks the merged member as admin`() {
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001", isAdmin = false)),
                group("Waitlist", member("+254700000001", isAdmin = true))
            )
        )

        assertTrue(merged.members.first().isAdminAnywhere)
    }

    @Test
    fun `a real name wins over a bare number as the display name`() {
        // Same person: unsaved in one group (shows as a number), push name in another.
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001", "+254700000001")),
                group("Waitlist", member("+254700000001", "Amina"))
            )
        )

        assertEquals("Amina", merged.members.first().displayName)
    }

    @Test
    fun `an existing real name is not overwritten by a bare number`() {
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001", "Amina")),
                group("Waitlist", member("+254700000001", "+254700000001"))
            )
        )

        assertEquals("Amina", merged.members.first().displayName)
    }

    @Test
    fun `members without numbers are never merged together on name`() {
        // Two different people can both render as "John". Collapsing them would
        // silently delete a real person from the extraction.
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member(null, "John")),
                group("Waitlist", member(null, "John"))
            )
        )

        assertEquals(2, merged.members.size)
        assertEquals(0, merged.duplicatesCollapsed)
    }

    @Test
    fun `withNumbers excludes the unreachable ones`() {
        val merged = ExtractionMerger.merge(
            listOf(group("Fifth", member("+254700000001"), member(null, "Saved Person")))
        )

        assertEquals(2, merged.members.size)
        assertEquals(1, merged.withNumbers.size)
    }

    @Test
    fun `the same group listed twice does not duplicate its source group entry`() {
        val merged = ExtractionMerger.merge(
            listOf(
                group("Fifth", member("+254700000001")),
                group("Fifth", member("+254700000001"))
            )
        )

        assertEquals(listOf("Fifth"), merged.members.first().sourceGroups)
    }

    @Test
    fun `merging nothing yields nothing`() {
        val merged = ExtractionMerger.merge(emptyList())
        assertTrue(merged.members.isEmpty())
        assertEquals(0, merged.duplicatesCollapsed)
    }

    @Test
    fun `an incomplete scroll is detectable from whatsapp's own count`() {
        val complete = GroupExtraction("Fifth", listOf(member("+254700000001")), reportedMemberCount = 1)
        assertFalse(complete.looksIncomplete)

        val truncated = GroupExtraction("Fifth", listOf(member("+254700000001")), reportedMemberCount = 824)
        assertTrue(truncated.looksIncomplete)
    }
}
