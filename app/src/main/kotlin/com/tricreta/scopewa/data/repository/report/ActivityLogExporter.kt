package com.tricreta.scopewa.data.repository.report

import com.tricreta.scopewa.data.repository.csv.CsvWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders the two Phase 6 exports:
 *
 * - [activityLogCsv] — the flat log, one row per message, which is what the
 *   client opens in a spreadsheet to answer "did this person get it?"
 * - [reportCsv] / [reportText] — one campaign summarised.
 *
 * Escaping is [CsvWriter]'s job, not this file's. That matters more here than
 * it did for contacts: a rendered WhatsApp message routinely contains commas,
 * quotes *and* line breaks, so an unescaped cell wouldn't be a cosmetic bug —
 * it would silently shift every column to its right.
 *
 * Pure Kotlin — no Android imports — so it is unit tested in CI.
 */
object ActivityLogExporter {

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val LOG_HEADER = listOf(
        "Time", "Campaign", "Name", "Number", "Status", "Reason", "Message"
    )

    fun activityLogCsv(
        entries: List<LoggedMessage>,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = CsvWriter.document(
        header = LOG_HEADER,
        rows = entries.map { entry ->
            listOf(
                formatTimestamp(entry.sentAt, zone),
                entry.campaignName,
                entry.displayName,
                entry.phoneE164,
                entry.status,
                entry.error.orEmpty(),
                entry.renderedText
            )
        }
    )

    /**
     * The report as two columns, Metric and Value. A wide one-row-per-campaign
     * shape would be neater to machine-read and much worse to actually look at
     * on a phone screen or in a spreadsheet, and this file is read by people.
     */
    fun reportCsv(
        report: CampaignReport,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = CsvWriter.document(
        header = listOf("Metric", "Value"),
        rows = reportRows(report, zone).map { listOf(it.first, it.second) }
    )

    /** The same figures as a block of text, for sharing into a chat. */
    fun reportText(
        report: CampaignReport,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = reportRows(report, zone).joinToString("\n") { (metric, value) ->
        "$metric: $value"
    }

    /** `activity-log-2026-07-29.csv`. */
    fun activityLogFileName(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "activity-log-${DATE.format(Instant.ofEpochMilli(atMillis).atZone(zone))}.csv"

    /** `report-launch-blast.csv`, slugged the same way Phase 2 slugs contact exports. */
    fun reportFileName(campaignName: String): String = "report-${slug(campaignName)}.csv"

    private fun reportRows(report: CampaignReport, zone: ZoneId): List<Pair<String, String>> =
        buildList {
            add("Campaign" to report.campaign.name)
            add("Status" to report.campaign.status)
            report.campaign.templateName?.let { add("Template" to it) }
            report.campaign.listName?.let { add("List" to it) }
            if (report.campaign.pacingProfile.isNotBlank()) {
                add("Pacing profile" to report.campaign.pacingProfile)
            }
            add("Started" to formatTimestamp(report.campaign.startedAt, zone))
            add("Finished" to formatTimestamp(report.campaign.finishedAt, zone))
            report.durationMillis?.let { add("Took" to formatDuration(it)) }

            add("Queued" to report.queued.toString())
            add("Sent" to report.sent.toString())
            add("Failed" to report.failed.toString())
            add("Skipped" to report.skipped.toString())
            add("Still queued" to report.pending.toString())
            add("Attempted (sent + failed)" to report.attempted.toString())
            add("Success rate" to "${report.successPercent}% of attempted")
            report.uniquenessPercent?.let { add("Uniqueness" to "$it% of sent messages were unique") }

            report.campaign.pauseReason?.takeIf { it.isNotBlank() }?.let {
                add("Paused because" to it)
            }

            report.skipsByCategory.forEach { add("Skipped — ${it.category.label}" to it.count.toString()) }
            report.failuresByReason.forEach { add("Failed — ${it.reason}" to it.count.toString()) }
        }

    private fun formatTimestamp(atMillis: Long?, zone: ZoneId): String =
        atMillis?.let { TIMESTAMP.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()

    /** `2h 14m`, `14m 05s`, `9s` — enough precision to compare two runs. */
    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun slug(label: String): String = label.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .ifBlank { "campaign" }
}
