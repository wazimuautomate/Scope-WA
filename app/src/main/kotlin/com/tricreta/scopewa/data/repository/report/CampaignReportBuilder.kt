package com.tricreta.scopewa.data.repository.report

import com.tricreta.scopewa.brain.uniqueness.UniquenessScorer

/**
 * Turns a campaign row plus its message rows into a [CampaignReport].
 *
 * A pure function on purpose: every number the client will read off the report
 * screen — success rate, skip breakdown, uniqueness — is decided here, where
 * CI can check it, rather than inside a `LazyColumn` or an SQL aggregate that
 * only a device can run.
 */
object CampaignReportBuilder {

    private const val SENT = "Sent"
    private const val FAILED = "Failed"
    private const val SKIPPED = "Skipped"
    private const val PENDING = "Pending"

    fun build(campaign: ReportCampaign, messages: List<LoggedMessage>): CampaignReport {
        val byStatus = messages.groupBy { it.status }
        val sent = byStatus[SENT].orEmpty()
        val failed = byStatus[FAILED].orEmpty()
        val skipped = byStatus[SKIPPED].orEmpty()
        val pending = byStatus[PENDING].orEmpty()

        return CampaignReport(
            campaign = campaign,
            queued = messages.size,
            sent = sent.size,
            failed = failed.size,
            skipped = skipped.size,
            pending = pending.size,
            skipsByCategory = tallySkips(skipped),
            failuresByReason = tallyFailures(failed),
            uniquenessPercent = uniquenessOf(sent)
        )
    }

    /**
     * Skips are bucketed, not listed verbatim: "Messaged in the last 30 days"
     * appearing 400 times is one fact, not 400.
     */
    private fun tallySkips(skipped: List<LoggedMessage>): List<SkipCount> =
        skipped.groupingBy { SkipCategory.classify(it.error) }
            .eachCount()
            .map { (category, count) -> SkipCount(category, count) }
            .sortedWith(compareByDescending<SkipCount> { it.count }.thenBy { it.category.ordinal })

    /**
     * Failures keep their sentence — unlike a skip, the exact wording is the
     * thing worth acting on ("WhatsApp didn't open" and "the compose box never
     * cleared" need different fixes).
     */
    private fun tallyFailures(failed: List<LoggedMessage>): List<ReasonCount> =
        failed.groupingBy { it.error?.trim().orEmpty().ifBlank { UNKNOWN_FAILURE } }
            .eachCount()
            .map { (reason, count) -> ReasonCount(reason, count) }
            .sortedWith(compareByDescending<ReasonCount> { it.count }.thenBy { it.reason })

    /**
     * Scored against what actually went out, not against the whole queue —
     * `MEMORY.md`'s point that the editor's meter is a floor and the real
     * figure is the rendered campaign's.
     */
    private fun uniquenessOf(sent: List<LoggedMessage>): Int? {
        val texts = sent.map { it.renderedText }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return null
        return UniquenessScorer.score(texts).uniquePercent
    }

    private const val UNKNOWN_FAILURE = "No reason recorded"
}
