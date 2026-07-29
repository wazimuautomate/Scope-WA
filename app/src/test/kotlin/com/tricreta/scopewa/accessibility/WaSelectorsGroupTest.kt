package com.tricreta.scopewa.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the group-extraction selectors added in Phase 4. The member-count
 * parsing matters most: it is the only signal that distinguishes a complete
 * extraction from one where scrolling stopped early.
 */
class WaSelectorsGroupTest {

    @Test
    fun `member count is parsed from the usual header wording`() {
        assertEquals(824, WaSelectors.parseReportedMemberCount("824 participants"))
        assertEquals(824, WaSelectors.parseReportedMemberCount("824 members"))
        assertEquals(7, WaSelectors.parseReportedMemberCount("7 participants"))
    }

    @Test
    fun `member count is parsed when wrapped in other text`() {
        assertEquals(824, WaSelectors.parseReportedMemberCount("Participants (824)"))
        assertEquals(824, WaSelectors.parseReportedMemberCount("Group · 824 members"))
    }

    @Test
    fun `thousands separators do not break the count`() {
        assertEquals(1024, WaSelectors.parseReportedMemberCount("1,024 participants"))
    }

    @Test
    fun `text without participant wording is not treated as a count`() {
        // Otherwise a chat message or an "about" line containing digits would be
        // read as the group size and wrongly mark an extraction incomplete.
        assertNull(WaSelectors.parseReportedMemberCount("824"))
        assertNull(WaSelectors.parseReportedMemberCount("Created 12 January"))
        assertNull(WaSelectors.parseReportedMemberCount("Media, links, and docs"))
    }

    @Test
    fun `blank input yields no count`() {
        assertNull(WaSelectors.parseReportedMemberCount(null))
        assertNull(WaSelectors.parseReportedMemberCount(""))
        assertNull(WaSelectors.parseReportedMemberCount("   "))
    }

    @Test
    fun `participant wording with no digits yields no count`() {
        assertNull(WaSelectors.parseReportedMemberCount("participants"))
    }

    @Test
    fun `group selectors are qualified for both whatsapp variants`() {
        assertEquals(
            "com.whatsapp:id/participants_list",
            WaSelectors.ParticipantList.qualifiedViewIds(WaSelectors.PACKAGE_WHATSAPP).first()
        )
        assertEquals(
            "com.whatsapp.w4b:id/participants_list",
            WaSelectors.ParticipantList.qualifiedViewIds(WaSelectors.PACKAGE_WHATSAPP_BUSINESS).first()
        )
    }

    @Test
    fun `admin label list is shared between the selector and the row parser`() {
        // The parser falls back to these when the badge has no view-id, so the
        // two must not drift apart.
        assertEquals(
            WaSelectors.ADMIN_LABEL_TEXTS,
            WaSelectors.ParticipantAdminBadge.texts
        )
    }
}
