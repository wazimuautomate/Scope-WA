package com.tricreta.scopewa.brain.groupadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GroupAddPlannerTest {

    private val planner = GroupAddPlanner(GroupAddPacer(Random(99)))

    private fun eligible(count: Int): List<AddCandidate> = (1..count).map {
        AddCandidate(
            phoneE164 = "+2547000000%02d".format(it),
            displayName = "Person $it",
            provenance = AddProvenance.RepliedBefore
        )
    }

    // ---- batching ----------------------------------------------------------

    @Test
    fun `batches are exactly three people`() {
        val plan = planner.plan(eligible(9), addedToday = 0)
        val sizes = plan.adds.groupBy { it.batchIndex }.map { it.value.size }
        assertEquals(listOf(3, 3, 3), sizes)
        assertEquals(3, plan.batchCount)
    }

    @Test
    fun `a partial last batch is allowed rather than padded or dropped`() {
        val plan = planner.plan(eligible(7), addedToday = 0)
        val sizes = plan.adds.groupBy { it.batchIndex }.map { it.value.size }
        assertEquals(listOf(3, 3, 1), sizes)
        assertEquals(7, plan.addCount)
    }

    @Test
    fun `there is a cooldown between batches and none after the last`() {
        val plan = planner.plan(eligible(9), addedToday = 0)
        assertEquals(2, plan.cooldowns.size)
        assertTrue(plan.steps.last() is GroupAddStep.Add)
    }

    @Test
    fun `one batch needs no cooldown at all`() {
        val plan = planner.plan(eligible(3), addedToday = 0)
        assertEquals(0, plan.cooldowns.size)
    }

    @Test
    fun `positions inside a batch never exceed the batch size`() {
        val plan = planner.plan(eligible(20), addedToday = 0)
        assertTrue(plan.adds.all { it.positionInBatch in 0..2 })
    }

    @Test
    fun `nobody is added twice and the order is preserved`() {
        val people = eligible(11)
        val plan = planner.plan(people, addedToday = 0)
        assertEquals(people.map { it.phoneE164 }, plan.adds.map { it.candidate.phoneE164 })
    }

    // ---- pacing ------------------------------------------------------------

    @Test
    fun `every planned delay falls inside 60 to 150 seconds`() {
        val plan = planner.plan(eligible(20), addedToday = 0)
        assertTrue(plan.adds.isNotEmpty())
        assertTrue(plan.adds.all { it.delaySecondsBefore in 60..150 })
    }

    @Test
    fun `every planned cooldown falls inside 8 to 15 minutes`() {
        val plan = planner.plan(eligible(20), addedToday = 0)
        assertTrue(plan.cooldowns.isNotEmpty())
        assertTrue(plan.cooldowns.all { it.seconds in (8 * 60)..(15 * 60) })
    }

    @Test
    fun `the first add waits too`() {
        // Opening a group and immediately adding someone is exactly the
        // fingerprint the delay exists to avoid.
        val plan = planner.plan(eligible(3), addedToday = 0)
        assertTrue(plan.adds.first().delaySecondsBefore >= 60)
    }

    // ---- the daily cap -----------------------------------------------------

    @Test
    fun `the daily cap of twenty is enforced`() {
        val plan = planner.plan(eligible(60), addedToday = 0)
        assertEquals(20, plan.addCount)
        assertEquals(40, plan.deferredToTomorrow.size)
        assertEquals(GroupAddStopReason.DailyCapReached, plan.endsWith)
    }

    @Test
    fun `adds already made today come off the allowance`() {
        val plan = planner.plan(eligible(20), addedToday = 15)
        assertEquals(5, plan.addCount)
        assertEquals(15, plan.deferredToTomorrow.size)
    }

    @Test
    fun `a used-up allowance plans nothing at all`() {
        val plan = planner.plan(eligible(10), addedToday = 20)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.steps.size)
        assertEquals(10, plan.deferredToTomorrow.size)
        assertEquals(GroupAddStopReason.DailyCapReached, plan.endsWith)
    }

    @Test
    fun `the cap counts adds made by other jobs on the same number`() {
        // Three jobs in one day share one allowance of 20 — a cap you can reset
        // by starting a second job is not a cap.
        val first = planner.plan(eligible(20), addedToday = 0)
        val second = planner.plan(eligible(20), addedToday = first.addCount)
        assertEquals(20, first.addCount)
        assertEquals(0, second.addCount)
    }

    @Test
    fun `a short list finishes rather than reporting the cap`() {
        val plan = planner.plan(eligible(4), addedToday = 0)
        assertEquals(GroupAddStopReason.QueueDrained, plan.endsWith)
        assertTrue(plan.deferredToTomorrow.isEmpty())
    }

    // ---- the eligibility backstop -----------------------------------------

    @Test
    fun `cold numbers are dropped even when a caller forgets to screen them`() {
        val mixed = listOf(
            AddCandidate("+254700000001", "Cold One", AddProvenance.Cold),
            AddCandidate("+254700000002", "Replied", AddProvenance.RepliedBefore)
        )
        val plan = planner.plan(mixed, addedToday = 0)
        assertEquals(1, plan.addCount)
        assertEquals("+254700000002", plan.adds.single().candidate.phoneE164)
    }

    // ---- the stop gate -----------------------------------------------------

    private fun gate(
        failures: Int = 0,
        restriction: Boolean = false,
        addedToday: Int = 0,
        remaining: Int = 5
    ) = GroupAddPlanner.stopReasonFor(
        GroupAddGateState(
            consecutiveFailures = failures,
            restrictionDialogSeen = restriction,
            addedToday = addedToday,
            remainingInQueue = remaining
        )
    )

    @Test
    fun `a healthy run is not stopped`() {
        assertNull(gate())
    }

    @Test
    fun `it stops after exactly two consecutive failures`() {
        assertNull(gate(failures = 0))
        assertNull(gate(failures = 1))
        assertEquals(GroupAddStopReason.ConsecutiveFailures, gate(failures = 2))
        assertEquals(GroupAddStopReason.ConsecutiveFailures, gate(failures = 3))
    }

    @Test
    fun `a skip is not a failure and never brings the run closer to stopping`() {
        // The loop only increments its counter on GroupAddOutcome.isFailure, so
        // this is the property that keeps skips out of the breaker.
        val skips = listOf(
            GroupAddOutcome.Skipped("opted out"),
            GroupAddOutcome.Skipped("no number"),
            GroupAddOutcome.AlreadyInGroup("already a participant")
        )
        var consecutiveFailures = 0
        skips.forEach { if (it.isFailure) consecutiveFailures++ }
        assertEquals(0, consecutiveFailures)
        assertNull(gate(failures = consecutiveFailures))
    }

    @Test
    fun `a privacy block is not a failure either`() {
        var consecutiveFailures = 0
        repeat(5) {
            val outcome = GroupAddOutcome.NeedsInviteLink("couldn't be added")
            if (outcome.isFailure) consecutiveFailures++
        }
        assertEquals(0, consecutiveFailures)
        assertNull(gate(failures = consecutiveFailures))
    }

    @Test
    fun `a restriction dialog outranks everything else`() {
        assertEquals(
            GroupAddStopReason.RestrictionDialogShown,
            gate(restriction = true, failures = 2, addedToday = 20, remaining = 0)
        )
    }

    @Test
    fun `the cap stops the run even with people still queued`() {
        assertEquals(GroupAddStopReason.DailyCapReached, gate(addedToday = 20, remaining = 40))
    }

    @Test
    fun `an empty queue is a clean finish`() {
        assertEquals(GroupAddStopReason.QueueDrained, gate(remaining = 0))
    }

    @Test
    fun `every stop reason explains itself`() {
        GroupAddStopReason.entries.forEach { assertTrue(it.label.isNotBlank()) }
        assertNotNull(GroupAddStopReason.DailyCapReached.label)
    }
}
