package com.tricreta.scopewa.brain.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRouterTest {

    private val njeri = InFlightRecipient("+254712345678", "Mama Njeri")
    private val otieno = InFlightRecipient("+254722000111", "Otieno")
    private val inFlight = listOf(njeri, otieno)

    private fun reply(
        senderName: String = "Mama Njeri",
        senderPhoneRaw: String? = null,
        text: String = "Nataka bundles mbili",
        isGroupMessage: Boolean = false
    ) = IncomingReply(
        senderName = senderName,
        senderPhoneRaw = senderPhoneRaw,
        text = text,
        timestampMillis = 1_700_000_000_000L,
        isGroupMessage = isGroupMessage,
        groupName = if (isGroupMessage) "Data Bundles Kenya" else null
    )

    // ---- opt-outs ----------------------------------------------------------

    @Test
    fun `STOP opts the sender out`() {
        assertEquals(
            ReplyRoute.MarkOptOut("+254712345678", "stop"),
            ReplyRouter.route(reply(text = "STOP"), inFlight)
        )
    }

    @Test
    fun `the Swahili opt-outs work the same way`() {
        // Two of the three keywords are Swahili — see OptOutDetector's doc.
        assertEquals(
            ReplyRoute.MarkOptOut("+254712345678", "acha"),
            ReplyRouter.route(reply(text = "Acha tafadhali"), inFlight)
        )
        assertEquals(
            ReplyRoute.MarkOptOut("+254712345678", "sitaki"),
            ReplyRouter.route(reply(text = "Sitaki hizi messages"), inFlight)
        )
    }

    @Test
    fun `the keyword decision is delegated, not reimplemented`() {
        // "don't stop" is not an opt-out. If this ever fails, someone has
        // duplicated OptOutDetector's guards here instead of calling it.
        assertEquals(
            ReplyRoute.RecordReply("+254712345678"),
            ReplyRouter.route(reply(text = "please don't stop sending these"), inFlight)
        )
        assertEquals(
            ReplyRoute.RecordReply("+254712345678"),
            ReplyRouter.route(reply(text = "can you stop by tomorrow"), inFlight)
        )
    }

    // ---- ordinary replies --------------------------------------------------

    @Test
    fun `an ordinary reply is recorded against the sender`() {
        assertEquals(
            ReplyRoute.RecordReply("+254712345678"),
            ReplyRouter.route(reply(), inFlight)
        )
    }

    @Test
    fun `name matching ignores case and surrounding space`() {
        assertEquals(
            ReplyRoute.RecordReply("+254712345678"),
            ReplyRouter.route(reply(senderName = "  mama njeri "), inFlight)
        )
    }

    @Test
    fun `an unsaved sender is matched by number, in whatever form WhatsApp showed it`() {
        // PhoneNormalizer turns a local 07… into +254… — architecture doc 5.2.
        assertEquals(
            ReplyRoute.RecordReply("+254712345678"),
            ReplyRouter.route(reply(senderName = "0712 345 678", senderPhoneRaw = "0712 345 678"), inFlight)
        )
        assertEquals(
            ReplyRoute.MarkOptOut("+254712345678", "stop"),
            ReplyRouter.route(
                reply(senderName = "+254 712 345 678", senderPhoneRaw = "+254 712 345 678", text = "Stop"),
                inFlight
            )
        )
    }

    // ---- everything the router refuses to guess at -------------------------

    @Test
    fun `a sender nobody messaged is ignored`() {
        val route = ReplyRouter.route(reply(senderName = "Some Friend"), inFlight)

        assertEquals(ReplyRoute.Ignore(ReplyRouter.REASON_UNKNOWN_SENDER), route)
    }

    @Test
    fun `an unknown number is ignored even when it says STOP`() {
        val route = ReplyRouter.route(
            reply(senderName = "+254733999888", senderPhoneRaw = "+254733999888", text = "STOP"),
            inFlight
        )

        assertEquals(ReplyRoute.Ignore(ReplyRouter.REASON_UNKNOWN_SENDER), route)
    }

    @Test
    fun `group messages are never treated as campaign replies`() {
        // A group carries no number to attribute the message to, and somebody
        // saying "acha" in a group of 700 is not unsubscribing from a campaign.
        assertEquals(
            ReplyRoute.Ignore(ReplyRouter.REASON_GROUP),
            ReplyRouter.route(reply(text = "acha", isGroupMessage = true), inFlight)
        )
    }

    @Test
    fun `an ambiguous name opts nobody out`() {
        // Marking the wrong person is permanent and silent. Missing this one
        // costs a reply count and leaves the hand-marking path intact.
        val twins = listOf(njeri, InFlightRecipient("+254799888777", "Mama Njeri"))

        assertEquals(
            ReplyRoute.Ignore(ReplyRouter.REASON_AMBIGUOUS_NAME),
            ReplyRouter.route(reply(text = "STOP"), twins)
        )
    }

    @Test
    fun `the same person listed twice is not ambiguous`() {
        val duplicated = listOf(njeri, njeri.copy(displayName = "mama njeri"))

        assertEquals(
            ReplyRoute.MarkOptOut("+254712345678", "stop"),
            ReplyRouter.route(reply(text = "STOP"), duplicated)
        )
    }

    @Test
    fun `nothing happens when no campaign has messaged anyone`() {
        assertEquals(
            ReplyRoute.Ignore(ReplyRouter.REASON_NOBODY_IN_FLIGHT),
            ReplyRouter.route(reply(text = "STOP"), emptyList())
        )
    }

    @Test
    fun `a nameless recipient row can't be matched by name`() {
        val nameless = listOf(InFlightRecipient("+254712345678", ""))
        val route = ReplyRouter.route(reply(senderName = "Mama Njeri"), nameless)

        assertTrue(route is ReplyRoute.Ignore)
    }
}
