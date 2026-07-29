package com.tricreta.scopewa.data.repository.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignReportBuilderTest {

    private val campaign = ReportCampaign(
        id = 1,
        name = "Launch blast",
        templateName = "Opening offer",
        listName = "Nairobi customers",
        status = "Completed",
        startedAt = 1_000L,
        finishedAt = 5_000L
    )

    private fun message(
        status: String,
        error: String? = null,
        text: String = "",
        phone: String = "+254712345678"
    ) = LoggedMessage(
        campaignId = 1,
        campaignName = "Launch blast",
        phoneE164 = phone,
        displayName = "Someone",
        renderedText = text,
        status = status,
        error = error
    )

    @Test
    fun `success rate is sent over attempted`() {
        val report = CampaignReportBuilder.build(
            campaign,
            List(3) { message("Sent", text = "hi $it") } + message("Failed", error = "WhatsApp didn't open")
        )

        assertEquals(4, report.queued)
        assertEquals(3, report.sent)
        assertEquals(1, report.failed)
        assertEquals(4, report.attempted)
        assertEquals(75, report.successPercent)
    }

    @Test
    fun `a skip is never a failure and never dilutes the success rate`() {
        val report = CampaignReportBuilder.build(
            campaign,
            listOf(
                message("Sent", text = "hi"),
                message("Skipped", error = "Opted out or blocked — never messaged"),
                message("Skipped", error = "Messaged in the last 30 days"),
                message("Skipped", error = "Duplicate number in the list")
            )
        )

        assertEquals(3, report.skipped)
        assertEquals(0, report.failed)
        assertEquals(1, report.attempted)
        // One sent, one attempted — three correct skips must not read as 25%.
        assertEquals(100, report.successPercent)
    }

    @Test
    fun `skips are bucketed by reason`() {
        val report = CampaignReportBuilder.build(
            campaign,
            listOf(
                message("Skipped", error = "Opted out or blocked — never messaged"),
                message("Skipped", error = "Opted out or blocked — never messaged"),
                message("Skipped", error = "Messaged in the last 30 days"),
                message("Skipped", error = "Duplicate number in the list"),
                message("Skipped", error = "Not a usable phone number")
            )
        )

        val counts = report.skipsByCategory.associate { it.category to it.count }
        assertEquals(2, counts[SkipCategory.OptedOut])
        assertEquals(1, counts[SkipCategory.Cooldown])
        assertEquals(1, counts[SkipCategory.Duplicate])
        assertEquals(1, counts[SkipCategory.InvalidNumber])
        // Biggest bucket first, so the screen reads top-down.
        assertEquals(SkipCategory.OptedOut, report.skipsByCategory.first().category)
    }

    @Test
    fun `a campaign where everything was skipped reports zero percent, not a crash`() {
        val report = CampaignReportBuilder.build(
            campaign,
            List(5) { message("Skipped", error = "Opted out or blocked — never messaged") }
        )

        assertEquals(5, report.queued)
        assertEquals(5, report.skipped)
        assertEquals(0, report.attempted)
        assertEquals(0.0, report.successRate, 0.0)
        assertEquals(0, report.successPercent)
        assertEquals(100, report.completionPercent)
        assertNull(report.uniquenessPercent)
    }

    @Test
    fun `an empty campaign is empty rather than divide-by-zero`() {
        val report = CampaignReportBuilder.build(campaign, emptyList())

        assertTrue(report.isEmpty)
        assertEquals(0, report.queued)
        assertEquals(0, report.successPercent)
        assertEquals(0, report.completionPercent)
        assertNull(report.uniquenessPercent)
        assertTrue(report.skipsByCategory.isEmpty())
        assertTrue(report.failuresByReason.isEmpty())
    }

    @Test
    fun `failures keep their exact wording and are counted biggest first`() {
        val report = CampaignReportBuilder.build(
            campaign,
            listOf(
                message("Failed", error = "The compose box never cleared"),
                message("Failed", error = "The compose box never cleared"),
                message("Failed", error = "WhatsApp didn't open"),
                message("Failed", error = null)
            )
        )

        assertEquals(
            listOf(
                ReasonCount("The compose box never cleared", 2),
                ReasonCount("No reason recorded", 1),
                ReasonCount("WhatsApp didn't open", 1)
            ),
            report.failuresByReason
        )
    }

    @Test
    fun `uniqueness is scored against what actually went out`() {
        val report = CampaignReportBuilder.build(
            campaign,
            listOf(
                message("Sent", text = "Habari Joy"),
                message("Sent", text = "Habari Otieno"),
                message("Sent", text = "Habari Otieno"),
                message("Sent", text = "Habari Mary"),
                // Never sent, so it must not count either way.
                message("Failed", text = "Habari Joy", error = "WhatsApp didn't open")
            )
        )

        // Four sent, two of them identical: 2 unique of 4.
        assertEquals(50, report.uniquenessPercent)
    }

    @Test
    fun `still-queued messages count toward completion but not toward the rate`() {
        val report = CampaignReportBuilder.build(
            campaign,
            List(2) { message("Sent", text = "hi $it") } + List(2) { message("Pending") }
        )

        assertEquals(4, report.queued)
        assertEquals(2, report.pending)
        assertEquals(50, report.completionPercent)
        assertEquals(100, report.successPercent)
    }

    @Test
    fun `duration is only reported when both ends are known`() {
        assertEquals(4_000L, CampaignReportBuilder.build(campaign, emptyList()).durationMillis)
        assertNull(
            CampaignReportBuilder
                .build(campaign.copy(finishedAt = null), emptyList())
                .durationMillis
        )
    }
}
