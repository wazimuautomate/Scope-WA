package com.tricreta.scopewa.brain.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptOutDetectorTest {

    @Test
    fun `a bare STOP opts out and reports the keyword lowercase`() {
        assertTrue(OptOutDetector.isOptOut("STOP"))
        assertEquals("stop", OptOutDetector.matchedKeyword("STOP"))
    }

    @Test
    fun `trailing punctuation does not hide the keyword`() {
        assertEquals("stop", OptOutDetector.matchedKeyword("stop."))
    }

    @Test
    fun `a polite two-word reply still opts out`() {
        assertEquals("stop", OptOutDetector.matchedKeyword("Please stop"))
    }

    @Test
    fun `the Swahili ACHA opts out`() {
        assertEquals("acha", OptOutDetector.matchedKeyword("ACHA"))
    }

    @Test
    fun `an emphatic ACHA with punctuation still opts out`() {
        assertEquals("acha", OptOutDetector.matchedKeyword("ACHA!"))
    }

    @Test
    fun `the Swahili SITAKI opts out`() {
        assertEquals("sitaki", OptOutDetector.matchedKeyword("SITAKI"))
    }

    @Test
    fun `unsubscribe opts out`() {
        assertEquals("unsubscribe", OptOutDetector.matchedKeyword("unsubscribe"))
    }

    @Test
    fun `the Swahili toa opts out`() {
        assertEquals("toa", OptOutDetector.matchedKeyword("Toa"))
    }

    @Test
    fun `a false positive is the dangerous direction, so a sentence merely containing the letters is not an opt-out`() {
        // Removing a real customer forever is silent and irreversible; leaving one
        // subscribed is recoverable. This case must fail safe.
        val reply = "I stopped by your shop yesterday and it was closed"

        assertNull(OptOutDetector.matchedKeyword(reply))
        assertFalse(OptOutDetector.isOptOut(reply))
    }

    @Test
    fun `the Swahili name Achieng does not unsubscribe anyone`() {
        assertFalse(OptOutDetector.isOptOut("Hi it is Achieng"))
    }

    @Test
    fun `a long paragraph is not an opt-out even when it contains the bare word stop`() {
        // Past a sentence's worth of words it reads as conversation, not an
        // instruction, and acting on it would remove a customer silently.
        assertNull(OptOutDetector.matchedKeyword("Please stop sending me these messages every single day"))
    }

    @Test
    fun `a polite full sentence asking us to stop is honoured`() {
        // A missed opt-out is not the safe direction: architecture doc section 2
        // names blocks and reports as the biggest ban signals there are, so
        // continuing to message someone who asked us to stop is what actually
        // gets the number banned.
        assertEquals("stop", OptOutDetector.matchedKeyword("Please stop sending me these messages"))
    }

    @Test
    fun `a short reply with words around the keyword still counts`() {
        assertEquals("acha", OptOutDetector.matchedKeyword("asante sana acha"))
        assertEquals("stop", OptOutDetector.matchedKeyword("please please please stop"))
    }

    @Test
    fun `dont stop is the opposite of an opt-out`() {
        assertNull(OptOutDetector.matchedKeyword("please don't stop"))
        assertNull(OptOutDetector.matchedKeyword("do not stop"))
    }

    @Test
    fun `stop by tomorrow is an arrangement to meet, not an unsubscribe`() {
        assertNull(OptOutDetector.matchedKeyword("can you stop by tomorrow"))
        assertNull(OptOutDetector.matchedKeyword("stop at the shop"))
    }

    @Test
    fun `a null reply is not an opt-out`() {
        assertNull(OptOutDetector.matchedKeyword(null))
        assertFalse(OptOutDetector.isOptOut(null))
    }

    @Test
    fun `a blank reply is not an opt-out`() {
        assertNull(OptOutDetector.matchedKeyword("   "))
        assertFalse(OptOutDetector.isOptOut(""))
    }

    @Test
    fun `a reply made only of punctuation is not an opt-out`() {
        assertNull(OptOutDetector.matchedKeyword("?!?!"))
    }

    @Test
    fun `every keyword is stored lowercase so a match can be compared directly`() {
        assertEquals(OptOutDetector.KEYWORDS.map { it.lowercase() }, OptOutDetector.KEYWORDS)
    }

    @Test
    fun `reasonFor formats the keyword for the opted-out reason column`() {
        assertEquals("Replied \"STOP\"", OptOutDetector.reasonFor("stop"))
        assertEquals("Replied \"ACHA\"", OptOutDetector.reasonFor("acha"))
    }
}
