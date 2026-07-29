package com.tricreta.scopewa.brain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientOrderingTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    private var nextId = 0L

    private fun candidate(
        phone: String,
        name: String = "Someone",
        isSaved: Boolean = false,
        timesReplied: Int = 0,
        optedOut: Boolean = false,
        lastMessagedAt: Long? = null
    ) = RecipientCandidate(
        contactId = ++nextId,
        phoneE164 = phone,
        displayName = name,
        isSaved = isSaved,
        timesReplied = timesReplied,
        optedOut = optedOut,
        lastMessagedAt = lastMessagedAt
    )

    @Test
    fun `people who replied before are messaged first, then saved contacts, then strangers`() {
        val plan = RecipientOrdering.plan(
            listOf(
                candidate("+254700000001", "Stranger"),
                candidate("+254700000002", "Saved", isSaved = true),
                candidate("+254700000003", "Replied", timesReplied = 2)
            )
        )

        assertEquals(listOf("Replied", "Saved", "Stranger"), plan.queue.map { it.displayName })
    }

    @Test
    fun `the source list's own order survives inside a tier`() {
        val plan = RecipientOrdering.plan(
            listOf(
                candidate("+254700000003", "Third", isSaved = true),
                candidate("+254700000001", "First", isSaved = true),
                candidate("+254700000002", "Second", isSaved = true)
            )
        )

        assertEquals(listOf("Third", "First", "Second"), plan.queue.map { it.displayName })
    }

    @Test
    fun `ordering is stable within every tier so a deliberately arranged list is not shuffled`() {
        val ordered = RecipientOrdering.order(
            listOf(
                candidate("+254700000001", "StrangerA"),
                candidate("+254700000002", "RepliedA", timesReplied = 1),
                candidate("+254700000003", "SavedA", isSaved = true),
                candidate("+254700000004", "StrangerB"),
                candidate("+254700000005", "RepliedB", timesReplied = 5),
                candidate("+254700000006", "SavedB", isSaved = true)
            )
        )

        assertEquals(
            listOf("RepliedA", "RepliedB", "SavedA", "SavedB", "StrangerA", "StrangerB"),
            ordered.map { it.displayName }
        )
    }

    @Test
    fun `someone who replied is in the replied tier even though they are also a saved contact`() {
        val both = candidate("+254700000001", "Both", isSaved = true, timesReplied = 1)

        assertEquals(RecipientOrdering.TIER_REPLIED, RecipientOrdering.tierOf(both))
    }

    @Test
    fun `an opted-out recipient is skipped and never reaches the queue`() {
        val plan = RecipientOrdering.plan(
            listOf(
                candidate("+254700000001", "Said stop", optedOut = true),
                candidate("+254700000002", "Fine")
            )
        )

        assertEquals(listOf("Fine"), plan.queue.map { it.displayName })
        assertEquals(SkipReason.OptedOut, plan.skipped.single().reason)
    }

    @Test
    fun `a suppressed number is skipped as opted out even when its contact row looks clean`() {
        val plan = RecipientOrdering.plan(
            candidates = listOf(
                candidate("+254700000001", "On the suppression list", timesReplied = 3),
                candidate("+254700000002", "Fine")
            ),
            suppressedNumbers = setOf("+254700000001")
        )

        assertEquals(listOf("Fine"), plan.queue.map { it.displayName })
        assertEquals(SkipReason.OptedOut, plan.skipped.single().reason)
        assertEquals("+254700000001", plan.skipped.single().candidate.phoneE164)
    }

    @Test
    fun `someone messaged inside the cooldown is skipped rather than messaged twice`() {
        val plan = RecipientOrdering.plan(
            candidates = listOf(candidate("+254700000001", "Two days ago", lastMessagedAt = now - 2 * day)),
            nowMillis = now,
            cooldownDays = 7
        )

        assertTrue(plan.queue.isEmpty())
        assertEquals(SkipReason.WithinCooldown, plan.skipped.single().reason)
    }

    @Test
    fun `someone messaged longer ago than the cooldown is queued again`() {
        val plan = RecipientOrdering.plan(
            candidates = listOf(candidate("+254700000001", "Ten days ago", lastMessagedAt = now - 10 * day)),
            nowMillis = now,
            cooldownDays = 7
        )

        assertEquals(listOf("Ten days ago"), plan.queue.map { it.displayName })
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun `the cooldown boundary itself is open - exactly N days ago is eligible`() {
        val plan = RecipientOrdering.plan(
            candidates = listOf(candidate("+254700000001", "Exactly seven days ago", lastMessagedAt = now - 7 * day)),
            nowMillis = now,
            cooldownDays = 7
        )

        assertEquals(1, plan.queuedCount)
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun `a cooldown of zero days disables the cooldown entirely`() {
        val plan = RecipientOrdering.plan(
            candidates = listOf(candidate("+254700000001", "Messaged a minute ago", lastMessagedAt = now - 60_000L)),
            nowMillis = now,
            cooldownDays = 0
        )

        assertEquals(1, plan.queuedCount)
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun `a number repeated in the list is queued once and the first occurrence is the one kept`() {
        val plan = RecipientOrdering.plan(
            listOf(
                candidate("+254700000001", "First copy"),
                candidate("+254700000001", "Second copy"),
                candidate("+254700000002", "Someone else")
            )
        )

        assertEquals(listOf("First copy", "Someone else"), plan.queue.map { it.displayName })
        assertEquals(SkipReason.DuplicateInList, plan.skipped.single().reason)
        assertEquals("Second copy", plan.skipped.single().candidate.displayName)
    }

    @Test
    fun `every candidate ends up in exactly one of queue or skipped, nobody is silently lost`() {
        val candidates = listOf(
            candidate("+254700000001", "Queued stranger"),
            candidate("+254700000002", "Queued replier", timesReplied = 1),
            candidate("+254700000001", "Duplicate"),
            candidate("+254700000003", "Opted out", optedOut = true),
            candidate("+254700000004", "Suppressed"),
            candidate("+254700000005", "Too recent", lastMessagedAt = now - day)
        )

        val plan = RecipientOrdering.plan(
            candidates = candidates,
            suppressedNumbers = setOf("+254700000004"),
            nowMillis = now,
            cooldownDays = 7
        )

        assertEquals(candidates.size, plan.queuedCount + plan.skippedCount)
        assertEquals(
            candidates.map { it.contactId }.sorted(),
            (plan.queue.map { it.contactId } + plan.skipped.map { it.candidate.contactId }).sorted()
        )
    }

    @Test
    fun `tierOf returns the documented tier constants`() {
        assertEquals(0, RecipientOrdering.TIER_REPLIED)
        assertEquals(1, RecipientOrdering.TIER_SAVED)
        assertEquals(2, RecipientOrdering.TIER_STRANGER)

        assertEquals(
            RecipientOrdering.TIER_REPLIED,
            RecipientOrdering.tierOf(candidate("+254700000001", timesReplied = 1))
        )
        assertEquals(
            RecipientOrdering.TIER_SAVED,
            RecipientOrdering.tierOf(candidate("+254700000002", isSaved = true))
        )
        assertEquals(
            RecipientOrdering.TIER_STRANGER,
            RecipientOrdering.tierOf(candidate("+254700000003"))
        )
    }

    @Test
    fun `an empty candidate list plans nothing`() {
        val plan = RecipientOrdering.plan(emptyList())

        assertEquals(0, plan.queuedCount)
        assertEquals(0, plan.skippedCount)
    }
}
