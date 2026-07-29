package com.tricreta.scopewa.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaSelectorsTest {

    @Test
    fun `view ids are qualified with whichever whatsapp package is in use`() {
        assertEquals(
            listOf("com.whatsapp:id/entry", "com.whatsapp:id/entry_text"),
            WaSelectors.ComposeBox.qualifiedViewIds(WaSelectors.PACKAGE_WHATSAPP)
        )
        assertEquals(
            listOf("com.whatsapp.w4b:id/entry", "com.whatsapp.w4b:id/entry_text"),
            WaSelectors.ComposeBox.qualifiedViewIds(WaSelectors.PACKAGE_WHATSAPP_BUSINESS)
        )
    }

    @Test
    fun `both whatsapp variants are supported and nothing else is`() {
        assertTrue(WaSelectors.isSupportedPackage("com.whatsapp"))
        assertTrue(WaSelectors.isSupportedPackage("com.whatsapp.w4b"))
        assertFalse(WaSelectors.isSupportedPackage("com.tricreta.scopewa"))
        assertFalse(WaSelectors.isSupportedPackage("com.android.settings"))
        assertFalse(WaSelectors.isSupportedPackage(null))
    }

    @Test
    fun `every selector the send path depends on has a locale-independent candidate`() {
        // Content-descriptions and text are localised; a Swahili phone will not
        // say "Type a message". Anything on the critical send path must be
        // findable by view-id alone.
        val critical = listOf(
            WaSelectors.ComposeBox,
            WaSelectors.SendButton,
            WaSelectors.ConversationTitle
        )
        for (selector in critical) {
            assertTrue(
                "${selector.name} must have at least one view-id candidate",
                selector.hasViewIdCandidates
            )
        }
    }

    @Test
    fun `restriction fragments match case-insensitively as substrings`() {
        val matched = WaSelectors.matchFragment(
            "Your account has been banned from using WhatsApp.",
            WaSelectors.RESTRICTION_TEXT_FRAGMENTS
        )
        assertEquals("your account has been banned", matched)
    }

    @Test
    fun `restriction matching survives surrounding text and odd casing`() {
        assertNotNull(
            WaSelectors.matchFragment(
                "  WARNING: You Can't Send Messages to this contact right now  ",
                WaSelectors.RESTRICTION_TEXT_FRAGMENTS
            )
        )
    }

    @Test
    fun `ordinary chat text is not mistaken for a restriction`() {
        assertNull(
            WaSelectors.matchFragment(
                "Thanks for signing up for the Data Challenge",
                WaSelectors.RESTRICTION_TEXT_FRAGMENTS
            )
        )
    }

    @Test
    fun `blank and null haystacks match nothing`() {
        assertNull(WaSelectors.matchFragment(null, WaSelectors.RESTRICTION_TEXT_FRAGMENTS))
        assertNull(WaSelectors.matchFragment("", WaSelectors.RESTRICTION_TEXT_FRAGMENTS))
        assertNull(WaSelectors.matchFragment("   ", WaSelectors.RESTRICTION_TEXT_FRAGMENTS))
    }

    @Test
    fun `privacy-blocked wording is recognised so those people go to the invite bucket`() {
        // Architecture doc section 3.2: these must never be retried as failures.
        assertNotNull(
            WaSelectors.matchFragment(
                "John couldn't be added to the group",
                WaSelectors.PRIVACY_BLOCKED_TEXT_FRAGMENTS
            )
        )
    }
}
