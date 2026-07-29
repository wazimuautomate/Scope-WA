package com.tricreta.scopewa.brain.reply

import com.tricreta.scopewa.accessibility.WaSelectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the one piece of the reply pipeline nobody can eyeball on a
 * handset in ten minutes — WhatsApp emits a dozen notification shapes and only
 * some are a person replying. These cases are the shapes worth being sure
 * about.
 */
class ReplyNotificationParserTest {

    private fun notification(
        packageName: String? = WaSelectors.PACKAGE_WHATSAPP,
        title: String? = "Mama Njeri",
        text: String? = "Nataka bundles mbili",
        subText: String? = null,
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        isSilent: Boolean = false,
        postedAtMillis: Long = 1_700_000_000_000L
    ) = RawNotification(
        packageName = packageName,
        title = title,
        text = text,
        subText = subText,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        isSilent = isSilent,
        postedAtMillis = postedAtMillis
    )

    // ---- the happy path ----------------------------------------------------

    @Test
    fun `an ordinary reply from a saved contact parses`() {
        val reply = ReplyNotificationParser.parse(notification())

        assertNotNull(reply)
        assertEquals("Mama Njeri", reply!!.senderName)
        assertEquals("Nataka bundles mbili", reply.text)
        assertEquals(1_700_000_000_000L, reply.timestampMillis)
        assertFalse(reply.isGroupMessage)
    }

    @Test
    fun `a saved contact carries no number, because the notification has none`() {
        assertNull(ReplyNotificationParser.parse(notification())!!.senderPhoneRaw)
    }

    @Test
    fun `an unsaved sender's title is recognised as the raw number`() {
        val reply = ReplyNotificationParser.parse(notification(title = "+254 712 345 678"))

        assertEquals("+254 712 345 678", reply!!.senderPhoneRaw)
    }

    @Test
    fun `a name that merely contains digits is not mistaken for a number`() {
        assertNull(ReplyNotificationParser.parse(notification(title = "Boss 0712345678"))!!.senderPhoneRaw)
        assertNull(ReplyNotificationParser.parse(notification(title = "Kim 254"))!!.senderPhoneRaw)
    }

    @Test
    fun `whatsapp business notifications are read too`() {
        // The client uses both apps — architecture doc section 10, Q1.
        assertNotNull(
            ReplyNotificationParser.parse(
                notification(packageName = WaSelectors.PACKAGE_WHATSAPP_BUSINESS)
            )
        )
    }

    // ---- rejections --------------------------------------------------------

    @Test
    fun `notifications from other apps are ignored`() {
        // Notification access is phone-wide; this is where the scoping happens.
        assertNull(ReplyNotificationParser.parse(notification(packageName = "com.android.mms")))
        assertNull(ReplyNotificationParser.parse(notification(packageName = "org.telegram.messenger")))
        assertNull(ReplyNotificationParser.parse(notification(packageName = null)))
    }

    @Test
    fun `the group summary roll-up is not a reply`() {
        assertNull(
            ReplyNotificationParser.parse(
                notification(
                    title = "WhatsApp",
                    text = "3 new messages from 2 chats",
                    isGroupSummary = true
                )
            )
        )
    }

    @Test
    fun `a count-style summary is rejected even when the summary flag is missing`() {
        // Some OEM builds don't set FLAG_GROUP_SUMMARY.
        assertNull(ReplyNotificationParser.parse(notification(title = "Chats", text = "5 new messages")))
        assertNull(ReplyNotificationParser.parse(notification(title = "Chats", text = "2 messages from 2 chats")))
    }

    @Test
    fun `a reply that merely starts with a number still parses`() {
        val reply = ReplyNotificationParser.parse(notification(text = "5 bundles please"))

        assertEquals("5 bundles please", reply!!.text)
    }

    @Test
    fun `whatsapp's own foreground notice is not a reply`() {
        assertNull(
            ReplyNotificationParser.parse(
                notification(title = "WhatsApp", text = "Checking for new messages", isOngoing = true)
            )
        )
        // …and is still rejected on a build that doesn't flag it as ongoing.
        assertNull(
            ReplyNotificationParser.parse(notification(title = "Chats", text = "Checking for new messages"))
        )
    }

    @Test
    fun `silent and ongoing notifications are ignored`() {
        assertNull(ReplyNotificationParser.parse(notification(isSilent = true)))
        assertNull(ReplyNotificationParser.parse(notification(isOngoing = true)))
    }

    @Test
    fun `a typing indicator is not a reply`() {
        assertNull(ReplyNotificationParser.parse(notification(text = "Mama Njeri is typing…")))
    }

    @Test
    fun `call notifications are not replies`() {
        assertNull(ReplyNotificationParser.parse(notification(text = "Missed voice call")))
        assertNull(ReplyNotificationParser.parse(notification(text = "Incoming video call")))
    }

    @Test
    fun `anything titled with the app's own name is the app talking about itself`() {
        assertNull(ReplyNotificationParser.parse(notification(title = "WhatsApp")))
        assertNull(ReplyNotificationParser.parse(notification(title = "WhatsApp Business")))
    }

    @Test
    fun `an empty title or body is rejected rather than guessed at`() {
        assertNull(ReplyNotificationParser.parse(notification(title = null)))
        assertNull(ReplyNotificationParser.parse(notification(text = null)))
        assertNull(ReplyNotificationParser.parse(notification(title = "   ")))
        assertNull(ReplyNotificationParser.parse(notification(text = "   ")))
    }

    // ---- groups ------------------------------------------------------------

    @Test
    fun `a group title splits into group and sender, and the body prefix is stripped`() {
        val reply = ReplyNotificationParser.parse(
            notification(
                title = "Data Bundles Kenya: Mama Njeri",
                text = "Mama Njeri: acha tafadhali"
            )
        )

        assertNotNull(reply)
        assertTrue(reply!!.isGroupMessage)
        assertEquals("Data Bundles Kenya", reply.groupName)
        assertEquals("Mama Njeri", reply.senderName)
        assertEquals("acha tafadhali", reply.text)
    }

    @Test
    fun `whatsapp's tilde marker on unsaved group participants is dropped`() {
        val reply = ReplyNotificationParser.parse(
            notification(title = "Data Bundles Kenya: ~Mama Njeri", text = "~Mama Njeri: sitaki")
        )

        assertEquals("Mama Njeri", reply!!.senderName)
        assertEquals("sitaki", reply.text)
    }

    @Test
    fun `a group name in subText is trusted only alongside a sender prefix`() {
        val grouped = ReplyNotificationParser.parse(
            notification(
                title = "Mama Njeri",
                text = "Mama Njeri: nataka bundles",
                subText = "Data Bundles Kenya"
            )
        )
        assertTrue(grouped!!.isGroupMessage)
        assertEquals("Data Bundles Kenya", grouped.groupName)
        assertEquals("nataka bundles", grouped.text)

        // No prefix in the body — subText alone must not turn a one-to-one chat
        // into a group, because ReplyRouter throws group messages away.
        val direct = ReplyNotificationParser.parse(
            notification(title = "Mama Njeri", text = "nataka bundles", subText = "Work account")
        )
        assertFalse(direct!!.isGroupMessage)
    }

    @Test
    fun `a colon inside an ordinary one-to-one message is left alone`() {
        val reply = ReplyNotificationParser.parse(
            notification(title = "Mama Njeri", text = "Ok: I'll take two")
        )

        assertFalse(reply!!.isGroupMessage)
        assertEquals("Mama Njeri", reply.senderName)
        assertEquals("Ok: I'll take two", reply.text)
    }

    @Test
    fun `a long sentence before a colon is not treated as a sender name`() {
        val body = "Please tell whoever is in charge of the bundles promotion: it worked"
        val reply = ReplyNotificationParser.parse(
            notification(title = "Data Bundles Kenya: Mama Njeri", text = body)
        )

        assertEquals(body, reply!!.text)
        assertEquals("Mama Njeri", reply.senderName)
    }
}
