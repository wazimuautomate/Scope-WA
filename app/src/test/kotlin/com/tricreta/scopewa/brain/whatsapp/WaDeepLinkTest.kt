package com.tricreta.scopewa.brain.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WaDeepLinkTest {

    @Test
    fun `strips the plus and builds a bare-digit link`() {
        assertEquals("https://wa.me/254712345678", WaDeepLink.chatUrl("+254712345678"))
    }

    @Test
    fun `strips separators a normaliser might leave behind`() {
        assertEquals("https://wa.me/254712345678", WaDeepLink.chatUrl("+254 712-345 678"))
    }

    @Test
    fun `appends url-encoded prefilled text`() {
        val url = WaDeepLink.chatUrl("+254712345678", "Hello there")
        assertEquals("https://wa.me/254712345678?text=Hello%20there", url)
    }

    @Test
    fun `encodes spaces as percent-20 rather than plus`() {
        // URLEncoder is form-encoding and would emit '+' for a space, which
        // WhatsApp renders literally — the message would arrive with plus
        // signs between every word.
        val url = WaDeepLink.chatUrl("+254712345678", "one two three")
        assertFalse("Query must not contain a raw '+'", url.substringAfter("?text=").contains("+"))
        assertTrue(url.endsWith("one%20two%20three"))
    }

    @Test
    fun `encodes characters that would otherwise break the url`() {
        val url = WaDeepLink.chatUrl("+254712345678", "50% off & more?")
        val query = url.substringAfter("?text=")
        assertFalse(query.contains("%  off"))
        assertTrue(query.contains("%25")) // the literal percent sign
        assertTrue(query.contains("%26")) // the ampersand
        assertTrue(query.contains("%3F")) // the question mark
    }

    @Test
    fun `handles the client's real message template with variables filled in`() {
        val message = "Brian, this is Skylink. Thanks for signing up. " +
            "Kindly find your receipt SKY-4821, and use it before 2026-08-05. " +
            "Reply STOP to never receive this."
        val url = WaDeepLink.chatUrl("+254712345678", message)

        assertTrue(url.startsWith("https://wa.me/254712345678?text="))
        assertFalse(url.contains(" "))
    }

    @Test
    fun `omits the query entirely when there is no prefilled text`() {
        assertEquals("https://wa.me/254712345678", WaDeepLink.chatUrl("+254712345678", null))
        assertEquals("https://wa.me/254712345678", WaDeepLink.chatUrl("+254712345678", ""))
    }

    @Test
    fun `rejects a number with no digits rather than silently opening the contact list`() {
        assertThrows(IllegalArgumentException::class.java) {
            WaDeepLink.chatUrl("not-a-number")
        }
    }
}
