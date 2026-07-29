package com.tricreta.scopewa.data.repository.report

/**
 * Phase 6's data model, deliberately decoupled from the Room entities the way
 * Phase 2's `ExportRecord` is. The mapping from `CampaignEntity` /
 * `CampaignMessageEntity` happens in the view models; everything in this
 * package stays plain Kotlin so CI unit tests the arithmetic without a phone.
 */

/** One finished message, as the log and the report see it. */
data class LoggedMessage(
    val campaignId: Long = 0,
    val campaignName: String = "",
    val phoneE164: String = "",
    val displayName: String = "",
    val renderedText: String = "",
    /** A [com.tricreta.scopewa.data.db.entity.MessageStatus] name. */
    val status: String = "Pending",
    val sentAt: Long? = null,
    /** Why it failed, or why it was skipped — free text written by the sender. */
    val error: String? = null
) {
    /** Never a blank cell in the export — the number stands in for a missing name. */
    val label: String get() = displayName.ifBlank { phoneE164 }
}

/** The campaign half of a report: everything that isn't derived from messages. */
data class ReportCampaign(
    val id: Long = 0,
    val name: String = "",
    val templateName: String? = null,
    val listName: String? = null,
    /** A [com.tricreta.scopewa.data.db.entity.CampaignStatus] name. */
    val status: String = "Draft",
    val pacingProfile: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Set when a circuit breaker or the daily cap stopped the run. */
    val pauseReason: String? = null
)

/**
 * Why a message was skipped, grouped into the buckets the client actually asks
 * about. The sender writes a sentence into `campaign_messages.error` rather
 * than an enum name — deliberately, because that sentence is what the Running
 * screen shows — so the report classifies it back out again here.
 */
enum class SkipCategory(val label: String) {
    OptedOut("Opted out"),
    Suppressed("Blocked number"),
    Cooldown("Inside the cooldown window"),
    Duplicate("Duplicate number in the list"),
    InvalidNumber("Not a usable number"),
    Other("Other");

    companion object {
        /**
         * Keyword match against the sentences `CampaignRepository.describeSkip`
         * writes. Order matters: "Opted out or blocked" mentions both, and the
         * opt-out is the more specific fact.
         */
        fun classify(error: String?): SkipCategory {
            val text = error.orEmpty().lowercase()
            return when {
                text.isBlank() -> Other
                text.contains("opted out") || text.contains("opt-out") -> OptedOut
                text.contains("cooldown") || text.contains("messaged in the last") -> Cooldown
                text.contains("duplicate") -> Duplicate
                text.contains("not a usable") || text.contains("invalid") ||
                    text.contains("no whatsapp") -> InvalidNumber
                text.contains("blocked") || text.contains("suppress") -> Suppressed
                else -> Other
            }
        }
    }
}

/** A reason and how often it came up, biggest first. */
data class ReasonCount(val reason: String, val count: Int)

/** A skip bucket and how often it came up, biggest first. */
data class SkipCount(val category: SkipCategory, val count: Int)

/**
 * The finished report for one campaign — architecture doc section 7's
 * "campaign result report".
 *
 * **A skip is not a failure** (see `MEMORY.md`). That decision is encoded here
 * rather than only described: [successRate] divides by [attempted], which is
 * sent plus failed and excludes skips entirely. A campaign that correctly
 * skipped half its list for opting out should not read as 50% successful.
 */
data class CampaignReport(
    val campaign: ReportCampaign = ReportCampaign(),
    val queued: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val pending: Int = 0,
    val skipsByCategory: List<SkipCount> = emptyList(),
    val failuresByReason: List<ReasonCount> = emptyList(),
    /** Percent of rendered texts that nobody else received; null when nothing was rendered. */
    val uniquenessPercent: Int? = null
) {
    /** Messages WhatsApp was actually asked to deliver. */
    val attempted: Int get() = sent + failed

    /** 0.0 to 1.0, of [attempted]. Zero attempts reads as 0.0, not as a crash. */
    val successRate: Double
        get() = if (attempted == 0) 0.0 else sent.toDouble() / attempted.toDouble()

    /** The headline number, rounded to a whole percent. */
    val successPercent: Int get() = Math.round(successRate * 100).toInt()

    /** How far through the queue the campaign got, skips included as "dealt with". */
    val completionPercent: Int
        get() = if (queued == 0) 0 else ((queued - pending) * 100) / queued

    /** Wall-clock run length in millis, when both ends are known. */
    val durationMillis: Long?
        get() {
            val start = campaign.startedAt ?: return null
            val end = campaign.finishedAt ?: return null
            return (end - start).takeIf { it >= 0 }
        }

    val isEmpty: Boolean get() = queued == 0
}
