package com.tricreta.scopewa.brain.groupadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAddOutcomeTest {

    @Test
    fun `outcomes land in the right bucket`() {
        assertEquals(GroupAddBucket.Added, GroupAddOutcome.Added(1_000).bucket)
        assertEquals(GroupAddBucket.NeedsInvite, GroupAddOutcome.NeedsInviteLink("x").bucket)
        assertEquals(GroupAddBucket.Skipped, GroupAddOutcome.AlreadyInGroup().bucket)
        assertEquals(GroupAddBucket.Skipped, GroupAddOutcome.Skipped("opted out").bucket)
        assertEquals(GroupAddBucket.Restricted, GroupAddOutcome.Restricted("banned").bucket)
        assertEquals(GroupAddBucket.Failed, GroupAddOutcome.NotReady("no service").bucket)
        assertEquals(GroupAddBucket.Failed, GroupAddOutcome.GroupNotReached("no group").bucket)
        assertEquals(GroupAddBucket.Failed, GroupAddOutcome.NumberNotFound("+254700000001").bucket)
        assertEquals(GroupAddBucket.Failed, GroupAddOutcome.NotConfirmed("count didn't move").bucket)
    }

    @Test
    fun `needs-invite never re-enters the queue`() {
        // Architecture doc section 8: a privacy block is "not a bug" and must
        // never be retried. This is the property the job runner relies on.
        assertFalse(GroupAddOutcome.NeedsInviteLink("privacy settings").isRetryable)
    }

    @Test
    fun `a queue rebuilt from outcomes drops the invite bucket and keeps real failures`() {
        val results = mapOf(
            "+254700000001" to GroupAddOutcome.NeedsInviteLink("couldn't be added"),
            "+254700000002" to GroupAddOutcome.NotConfirmed("the count didn't move"),
            "+254700000003" to GroupAddOutcome.Added(500),
            "+254700000004" to GroupAddOutcome.AlreadyInGroup(),
            "+254700000005" to GroupAddOutcome.Skipped("opted out mid-run")
        )

        val requeued = results.filterValues { it.isRetryable }.keys

        assertFalse("+254700000001" in requeued)
        assertTrue("+254700000002" in requeued)
        assertFalse("+254700000003" in requeued)
        assertFalse("+254700000004" in requeued)
        // A skip can be legitimately retried later — the reason may have gone
        // away — which is a different question from whether it was a failure.
        assertTrue("+254700000005" in requeued)
    }

    @Test
    fun `only real failures count toward the two-in-a-row breaker`() {
        assertFalse(GroupAddOutcome.Added(1).isFailure)
        assertFalse(GroupAddOutcome.NeedsInviteLink("x").isFailure)
        assertFalse(GroupAddOutcome.AlreadyInGroup().isFailure)
        assertFalse(GroupAddOutcome.Skipped("x").isFailure)
        // A restriction stops everything on its own; counting it as a failure
        // as well would double-report the same event.
        assertFalse(GroupAddOutcome.Restricted("banned").isFailure)

        assertTrue(GroupAddOutcome.NotReady("x").isFailure)
        assertTrue(GroupAddOutcome.GroupNotReached("x").isFailure)
        assertTrue(GroupAddOutcome.NumberNotFound("x").isFailure)
        assertTrue(GroupAddOutcome.NotConfirmed("x").isFailure)
    }

    @Test
    fun `only a restriction stops the whole job`() {
        assertTrue(GroupAddOutcome.Restricted("banned").stopsEverything)
        assertFalse(GroupAddOutcome.NotConfirmed("x").stopsEverything)
        assertFalse(GroupAddOutcome.NeedsInviteLink("x").stopsEverything)
    }

    @Test
    fun `only Added reports as added`() {
        assertTrue(GroupAddOutcome.Added(1).isAdded)
        assertFalse(GroupAddOutcome.AlreadyInGroup().isAdded)
        assertFalse(GroupAddOutcome.NeedsInviteLink("x").isAdded)
    }

    @Test
    fun `every outcome explains itself in words a client can act on`() {
        val all = listOf(
            GroupAddOutcome.Added(1),
            GroupAddOutcome.NeedsInviteLink("privacy settings"),
            GroupAddOutcome.AlreadyInGroup(),
            GroupAddOutcome.AlreadyInGroup("already a participant"),
            GroupAddOutcome.Skipped("opted out"),
            GroupAddOutcome.NotReady("service not connected"),
            GroupAddOutcome.GroupNotReached("no such group"),
            GroupAddOutcome.NumberNotFound("+254700000001"),
            GroupAddOutcome.NotConfirmed("the count didn't move"),
            GroupAddOutcome.Restricted("your account has been banned")
        )
        all.forEach { assertTrue(it.describe().isNotBlank()) }

        // The invite wording has to say "send the link", not "try again".
        assertTrue(
            GroupAddOutcome.NeedsInviteLink("x").describe().contains("invite link", ignoreCase = true)
        )
    }

    @Test
    fun `every bucket has a label for the results screen`() {
        GroupAddBucket.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
