package com.tricreta.scopewa.brain.uniqueness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniquenessSummaryTest {

    @Test
    fun `reads exactly like the meter in the architecture doc`() {
        // 194 one-off messages plus three duplicated pairs = 200 sent, 6 people
        // receiving text somebody else also got.
        val messages = List(194) { "unique-$it" } +
            listOf("dup-a", "dup-a", "dup-b", "dup-b", "dup-c", "dup-c")

        val result = UniquenessScorer.score(messages)

        assertEquals(200, result.total)
        assertEquals(6, result.duplicateCount)
        assertEquals(
            "200 messages · 194 unique (97%) · 6 exact duplicates",
            result.summaryLine()
        )
        assertEquals(
            "6 people would get identical text. Add more spintax options.",
            result.warningLine()
        )
    }

    @Test
    fun `a fully unique campaign gets no warning`() {
        val result = UniquenessScorer.score(List(50) { "message-$it" })

        assertEquals("50 messages · 50 unique (100%) · 0 exact duplicates", result.summaryLine())
        assertNull(result.warningLine())
    }

    @Test
    fun `an identical blast warns about every recipient`() {
        val result = UniquenessScorer.score(List(200) { "Hi, this is Skylink." })

        assertEquals("200 messages · 0 unique (0%) · 200 exact duplicates", result.summaryLine())
        assertEquals(
            "200 people would get identical text. Add more spintax options.",
            result.warningLine()
        )
    }

    @Test
    fun `an empty campaign has nothing to warn about`() {
        val result = UniquenessScorer.score(emptyList())

        assertEquals("0 messages · 0 unique (100%) · 0 exact duplicates", result.summaryLine())
        assertNull(result.warningLine())
    }
}
