package com.tricreta.scopewa.brain.groupadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * These tests pin the numbers from architecture doc section 6 layer 5 in place.
 *
 * They are deliberately literal — asserting `3`, `60`, `150`, `8`, `15`, `20`,
 * `2` rather than reading the constants back — so that loosening a constant
 * *fails CI* instead of quietly changing the product. `CLAUDE.md`: the pacing
 * numbers are the product, not tuning knobs.
 */
class GroupAddPacingTest {

    @Test
    fun `the layer 5 numbers are exactly what the architecture doc says`() {
        assertEquals(3, GroupAddPacing.BATCH_SIZE)
        assertEquals(60, GroupAddPacing.MIN_DELAY_SECONDS)
        assertEquals(150, GroupAddPacing.MAX_DELAY_SECONDS)
        assertEquals(8, GroupAddPacing.MIN_COOLDOWN_MINUTES)
        assertEquals(15, GroupAddPacing.MAX_COOLDOWN_MINUTES)
        assertEquals(20, GroupAddPacing.DAILY_CAP)
        assertEquals(2, GroupAddPacing.MAX_CONSECUTIVE_FAILURES)
    }

    @Test
    fun `the group-add failure breaker is stricter than the send-side one`() {
        // Section 6 layer 4 allows three; layer 5 allows two. If these ever
        // match, one of them has drifted.
        assertTrue(GroupAddPacing.MAX_CONSECUTIVE_FAILURES < 3)
    }

    @Test
    fun `every drawn delay falls inside 60 to 150 seconds`() {
        val pacer = GroupAddPacer(Random(1234))
        repeat(2_000) {
            val delay = pacer.nextDelaySeconds()
            assertTrue("delay $delay out of range", delay in 60..150)
        }
    }

    @Test
    fun `every drawn cooldown falls inside 8 to 15 minutes`() {
        val pacer = GroupAddPacer(Random(4321))
        repeat(2_000) {
            val cooldown = pacer.nextCooldownSeconds()
            assertTrue("cooldown $cooldown out of range", cooldown in (8 * 60)..(15 * 60))
        }
    }

    @Test
    fun `delays are not a fixed interval`() {
        // A steady gap is itself a fingerprint — the randomiser has to actually
        // randomise, not just sit inside the range.
        val pacer = GroupAddPacer(Random(7))
        val drawn = List(50) { pacer.nextDelaySeconds() }.toSet()
        assertTrue("expected varied delays, got $drawn", drawn.size > 5)
    }

    @Test
    fun `the daily allowance shrinks as adds are made and never goes negative`() {
        assertEquals(20, GroupAddPacing.remainingToday(0))
        assertEquals(1, GroupAddPacing.remainingToday(19))
        assertEquals(0, GroupAddPacing.remainingToday(20))
        assertEquals(0, GroupAddPacing.remainingToday(500))
    }

    @Test
    fun `the cap is reached at twenty, not after it`() {
        assertFalse(GroupAddPacing.isDailyCapReached(19))
        assertTrue(GroupAddPacing.isDailyCapReached(20))
        assertTrue(GroupAddPacing.isDailyCapReached(21))
    }

    @Test
    fun `a cooldown is owed after every third add and not before`() {
        assertFalse(GroupAddPacing.isBatchComplete(0))
        assertFalse(GroupAddPacing.isBatchComplete(1))
        assertFalse(GroupAddPacing.isBatchComplete(2))
        assertTrue(GroupAddPacing.isBatchComplete(3))
        assertFalse(GroupAddPacing.isBatchComplete(4))
        assertTrue(GroupAddPacing.isBatchComplete(6))
    }
}
