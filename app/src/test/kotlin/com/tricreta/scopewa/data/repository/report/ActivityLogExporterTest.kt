package com.tricreta.scopewa.data.repository.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ActivityLogExporterTest {

    private val utc = ZoneId.of("UTC")

    private val sent = LoggedMessage(
        campaignId = 1,
        campaignName = "Launch blast",
        phoneE164 = "+254712345678",
        displayName = "Joy Wanjiru",
        renderedText = "Habari Joy",
        status = "Sent",
        sentAt = 0L
    )

    @Test
    fun `the log has a BOM, a header row and CRLF endings so Excel opens it correctly`() {
        val csv = ActivityLogExporter.activityLogCsv(listOf(sent), utc)

        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(
            "\uFEFFTime,Campaign,Name,Number,Status,Reason,Message\r\n" +
                "1970-01-01 00:00:00,Launch blast,Joy Wanjiru,+254712345678,Sent,,Habari Joy\r\n",
            csv
        )
    }

    @Test
    fun `a message containing commas, quotes and newlines stays in its own cell`() {
        val awkward = sent.copy(
            renderedText = "Hi, \"Joy\"\nOffer ends Friday",
            displayName = "Joy, of Nairobi"
        )

        val csv = ActivityLogExporter.activityLogCsv(listOf(awkward), utc)
        val body = csv.removePrefix("\uFEFF").split("\r\n")[1]

        assertEquals(
            "1970-01-01 00:00:00,Launch blast,\"Joy, of Nairobi\",+254712345678,Sent,," +
                "\"Hi, \"\"Joy\"\"\nOffer ends Friday\"",
            body
        )
    }

    @Test
    fun `a skipped message exports with its reason and no timestamp`() {
        val skipped = sent.copy(
            status = "Skipped",
            sentAt = null,
            renderedText = "",
            error = "Opted out or blocked — never messaged"
        )

        val body = ActivityLogExporter.activityLogCsv(listOf(skipped), utc)
            .removePrefix("\uFEFF").split("\r\n")[1]

        assertTrue(body.startsWith(","))
        assertTrue(body.contains("Skipped,Opted out or blocked — never messaged,"))
    }

    @Test
    fun `an empty log is still a valid file with its header`() {
        val csv = ActivityLogExporter.activityLogCsv(emptyList(), utc)

        assertEquals("\uFEFFTime,Campaign,Name,Number,Status,Reason,Message\r\n", csv)
    }

    @Test
    fun `the report csv names the campaign, the totals and the success rate`() {
        val report = CampaignReportBuilder.build(
            ReportCampaign(
                name = "Launch blast",
                templateName = "Opening offer",
                listName = "Nairobi customers",
                status = "Completed",
                startedAt = 0L,
                finishedAt = 60_000L
            ),
            listOf(
                sent,
                sent.copy(renderedText = "Habari Otieno"),
                sent.copy(status = "Failed", error = "WhatsApp didn't open"),
                sent.copy(status = "Skipped", error = "Messaged in the last 30 days")
            )
        )

        val csv = ActivityLogExporter.reportCsv(report, utc)

        assertTrue(csv.startsWith("\uFEFFMetric,Value\r\n"))
        assertTrue(csv.contains("Campaign,Launch blast\r\n"))
        assertTrue(csv.contains("Template,Opening offer\r\n"))
        assertTrue(csv.contains("List,Nairobi customers\r\n"))
        assertTrue(csv.contains("Queued,4\r\n"))
        assertTrue(csv.contains("Sent,2\r\n"))
        assertTrue(csv.contains("Failed,1\r\n"))
        assertTrue(csv.contains("Skipped,1\r\n"))
        assertTrue(csv.contains("Success rate,67% of attempted\r\n"))
        assertTrue(csv.contains("Took,1m 0s\r\n"))
        assertTrue(csv.contains("Skipped — Inside the cooldown window,1\r\n"))
        assertTrue(csv.contains("Failed — WhatsApp didn't open,1\r\n"))
    }

    @Test
    fun `a report for a campaign that only skipped says so without claiming failures`() {
        val report = CampaignReportBuilder.build(
            ReportCampaign(name = "Quiet run", status = "Completed"),
            List(3) {
                sent.copy(status = "Skipped", sentAt = null, error = "Opted out or blocked")
            }
        )

        val text = ActivityLogExporter.reportText(report, utc)

        assertTrue(text.contains("Skipped: 3"))
        assertTrue(text.contains("Failed: 0"))
        assertTrue(text.contains("Success rate: 0% of attempted"))
        assertTrue(text.contains("Skipped — Opted out: 3"))
        // Nothing went out, so there is no honest uniqueness figure to print.
        assertFalse(text.contains("Uniqueness"))
    }

    @Test
    fun `file names are slugged and dated`() {
        assertEquals("report-launch-blast.csv", ActivityLogExporter.reportFileName("Launch Blast!"))
        assertEquals("report-campaign.csv", ActivityLogExporter.reportFileName("   "))
        assertEquals("activity-log-1970-01-01.csv", ActivityLogExporter.activityLogFileName(0L, utc))
    }
}
