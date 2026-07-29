package com.tricreta.scopewa.data.repository.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionFilterTest {

    private fun member(
        number: String?,
        name: String = "Someone",
        isAdmin: Boolean = false,
        isSelf: Boolean = false
    ) = ExtractedMember(
        rawTitle = number ?: name,
        displayName = name,
        phoneE164 = number,
        numberStatus = if (number != null) MemberNumberStatus.Visible else MemberNumberStatus.NotShown,
        isAdmin = isAdmin,
        isSelf = isSelf
    )

    @Test
    fun `by default unreachable members and self are dropped, admins kept`() {
        val members = listOf(
            member("+254700000001"),
            member(null, "Saved Person"),
            member("+254700000002", isAdmin = true),
            member("+254700000003", isSelf = true)
        )

        val outcome = ExtractionFilter.apply(members, ExtractionFilters())

        assertEquals(2, outcome.kept.size)
        assertEquals(1, outcome.droppedWithoutNumbers)
        assertEquals(1, outcome.droppedSelf)
        assertEquals(0, outcome.droppedAdmins)
    }

    @Test
    fun `excluding admins removes them`() {
        val members = listOf(
            member("+254700000001"),
            member("+254700000002", isAdmin = true)
        )

        val outcome = ExtractionFilter.apply(members, ExtractionFilters(excludeAdmins = true))

        assertEquals(1, outcome.kept.size)
        assertEquals(1, outcome.droppedAdmins)
    }

    @Test
    fun `excluding saved removes numbers already in the database`() {
        val members = listOf(
            member("+254700000001"),
            member("+254700000002")
        )

        val outcome = ExtractionFilter.apply(
            members,
            ExtractionFilters(excludeSaved = true),
            savedNumbers = setOf("+254700000002")
        )

        assertEquals(listOf("+254700000001"), outcome.kept.map { it.phoneE164 })
        assertEquals(1, outcome.droppedSaved)
    }

    @Test
    fun `unreachable members can be kept deliberately so counts stay honest`() {
        val members = listOf(member("+254700000001"), member(null, "Saved Person"))

        val outcome = ExtractionFilter.apply(
            members,
            ExtractionFilters(excludeWithoutNumbers = false)
        )

        assertEquals(2, outcome.kept.size)
        assertEquals(0, outcome.droppedWithoutNumbers)
    }

    @Test
    fun `drop counts explain the whole difference between input and output`() {
        // This is the property the UI depends on: "824 read → 310 kept" must
        // always be fully accounted for, or the user can't trust the export.
        val members = listOf(
            member("+254700000001"),
            member("+254700000002", isAdmin = true),
            member(null, "Saved A"),
            member(null, "Saved B"),
            member("+254700000003", isSelf = true),
            member("+254700000004")
        )

        val outcome = ExtractionFilter.apply(
            members,
            ExtractionFilters(excludeAdmins = true, excludeSaved = true),
            savedNumbers = setOf("+254700000004")
        )

        assertEquals(members.size, outcome.kept.size + outcome.totalDropped)
    }

    @Test
    fun `self is dropped before any other reason so it is never double counted`() {
        val members = listOf(member("+254700000001", isAdmin = true, isSelf = true))

        val outcome = ExtractionFilter.apply(members, ExtractionFilters(excludeAdmins = true))

        assertEquals(1, outcome.droppedSelf)
        assertEquals(0, outcome.droppedAdmins)
        assertTrue(outcome.kept.isEmpty())
    }

    @Test
    fun `an empty extraction filters to nothing without error`() {
        val outcome = ExtractionFilter.apply(emptyList(), ExtractionFilters())
        assertTrue(outcome.kept.isEmpty())
        assertEquals(0, outcome.totalDropped)
    }
}
