package com.tricreta.scopewa.brain.groupadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hard rule from architecture doc section 6 layer 5: only people who have
 * messaged before, or who came from a group they already belong to. Never cold
 * numbers.
 */
class GroupAddEligibilityTest {

    private fun candidate(
        number: String,
        provenance: AddProvenance,
        name: String = ""
    ) = AddCandidate(phoneE164 = number, displayName = name, provenance = provenance)

    @Test
    fun `cold numbers are always rejected`() {
        val split = GroupAddEligibility.partition(
            listOf(
                candidate("+254700000001", AddProvenance.Cold),
                candidate("+254700000002", AddProvenance.Cold),
                candidate("+254700000003", AddProvenance.RepliedBefore)
            )
        )

        assertEquals(1, split.eligibleCount)
        assertEquals("+254700000003", split.eligible.single().phoneE164)
        assertEquals(2, split.coldCount)
        assertTrue(split.rejected.all { it.reason.isNotBlank() })
    }

    @Test
    fun `a list of nothing but cold numbers leaves nobody to add`() {
        val split = GroupAddEligibility.partition(
            (1..50).map { candidate("+25470000%04d".format(it), AddProvenance.Cold) }
        )
        assertTrue(split.hasNobody)
        assertEquals(50, split.coldCount)
    }

    @Test
    fun `people who replied and people from a shared group are both eligible`() {
        val split = GroupAddEligibility.partition(
            listOf(
                candidate("+254700000001", AddProvenance.RepliedBefore),
                candidate("+254700000002", AddProvenance.SharedGroup("Data Challenge"))
            )
        )
        assertEquals(2, split.eligibleCount)
        assertEquals(0, split.rejectedCount)
    }

    @Test
    fun `opting out of messages also opts out of being added to a group`() {
        val split = GroupAddEligibility.partition(
            candidates = listOf(candidate("+254700000001", AddProvenance.RepliedBefore)),
            optedOutNumbers = setOf("+254700000001")
        )
        assertTrue(split.hasNobody)
        assertEquals(1, split.rejectedCount)
        // Not a cold rejection — a different reason, and the screen says so.
        assertEquals(0, split.coldCount)
        assertEquals(GroupAddEligibility.OPTED_OUT_REASON, split.rejected.single().reason)
    }

    @Test
    fun `a blank number is rejected without being called cold`() {
        val split = GroupAddEligibility.partition(
            listOf(candidate("", AddProvenance.RepliedBefore, name = "No Number"))
        )
        assertTrue(split.hasNobody)
        assertEquals(GroupAddEligibility.NO_NUMBER_REASON, split.rejected.single().reason)
        assertFalse(split.rejected.single().isCold)
    }

    @Test
    fun `the same person in two source groups is only offered once`() {
        val split = GroupAddEligibility.partition(
            listOf(
                candidate("+254700000001", AddProvenance.SharedGroup("Group A"), name = "Asha"),
                candidate("+254700000001", AddProvenance.SharedGroup("Group B"), name = "Asha")
            )
        )
        assertEquals(1, split.eligibleCount)
    }

    @Test
    fun `a duplicate keeps the strongest provenance`() {
        val split = GroupAddEligibility.partition(
            listOf(
                candidate("+254700000001", AddProvenance.Cold),
                candidate("+254700000001", AddProvenance.RepliedBefore)
            )
        )
        assertEquals(1, split.eligibleCount)
        assertEquals(AddProvenance.RepliedBefore, split.eligible.single().provenance)
    }

    @Test
    fun `two different people with no number are both reported`() {
        val split = GroupAddEligibility.partition(
            listOf(
                candidate("", AddProvenance.RepliedBefore, name = "One"),
                candidate("", AddProvenance.RepliedBefore, name = "Two")
            )
        )
        assertEquals(2, split.rejectedCount)
    }

    @Test
    fun `provenance round-trips through its code`() {
        assertEquals(
            AddProvenance.RepliedBefore,
            AddProvenance.fromCode(AddProvenance.RepliedBefore.code)
        )
        assertEquals(
            AddProvenance.SharedGroup("Data Challenge"),
            AddProvenance.fromCode(AddProvenance.SharedGroup("Data Challenge").code)
        )
        assertEquals(AddProvenance.Cold, AddProvenance.fromCode(AddProvenance.Cold.code))
    }

    @Test
    fun `an unreadable provenance decodes to cold, never to addable`() {
        assertEquals(AddProvenance.Cold, AddProvenance.fromCode(null))
        assertEquals(AddProvenance.Cold, AddProvenance.fromCode(""))
        assertEquals(AddProvenance.Cold, AddProvenance.fromCode("something-corrupt"))
    }

    @Test
    fun `only cold is ineligible`() {
        assertTrue(AddProvenance.RepliedBefore.isEligible)
        assertTrue(AddProvenance.SharedGroup("x").isEligible)
        assertFalse(AddProvenance.Cold.isEligible)
    }
}
