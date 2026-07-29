package com.tricreta.scopewa.brain.reply

import com.tricreta.scopewa.brain.campaign.OptOutDetector
import com.tricreta.scopewa.brain.phone.NormalizedPhone
import com.tricreta.scopewa.brain.phone.PhoneNormalizer

/** Somebody a campaign has messaged recently and could plausibly be replying to. */
data class InFlightRecipient(
    val phoneE164: String,
    val displayName: String = ""
)

/** The one thing the repository should do about an incoming reply. */
sealed interface ReplyRoute {

    /** Section 6 layer 3: permanent, global, and applied without asking. */
    data class MarkOptOut(val phoneE164: String, val matchedKeyword: String) : ReplyRoute

    /** An ordinary reply. Counts toward recipient hygiene and the cold-batch breaker. */
    data class RecordReply(val phoneE164: String) : ReplyRoute

    /** Nothing to do. [reason] is for diagnostics; it never contains message text. */
    data class Ignore(val reason: String) : ReplyRoute
}

/**
 * Decides what an [IncomingReply] means for a running campaign.
 *
 * The keyword decision is delegated wholesale to [OptOutDetector] — that list
 * ("stop", "acha", "sitaki", …) and its negation guards are the product, and
 * duplicating them here is how the two copies would drift apart.
 *
 * ## What this refuses to do
 *
 * - **Attribute a group message.** A group notification carries a display name
 *   and no number, and somebody saying "acha" in a group of 700 is not
 *   unsubscribing from a campaign they were messaged privately about.
 * - **Attribute an ambiguous name.** Two recipients called "Mama Njeri" and one
 *   reply from "Mama Njeri" is not enough to opt out either of them. Marking
 *   the wrong person is permanent and silent; missing this one costs a reply
 *   count and leaves the hand-marking path that already exists.
 * - **Attribute anyone who was not messaged.** A reply from a stranger is a
 *   normal WhatsApp conversation and none of this app's business.
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
object ReplyRouter {

    const val REASON_GROUP = "Group message — not a reply to a campaign"
    const val REASON_NOBODY_IN_FLIGHT = "No campaign has messaged anyone recently"
    const val REASON_UNKNOWN_SENDER = "Sender isn't a recipient of any recent campaign"
    const val REASON_AMBIGUOUS_NAME = "More than one recipient has that name"

    fun route(
        reply: IncomingReply,
        inFlight: Collection<InFlightRecipient>,
        normalizer: PhoneNormalizer = PhoneNormalizer()
    ): ReplyRoute {
        if (reply.isGroupMessage) return ReplyRoute.Ignore(REASON_GROUP)
        if (inFlight.isEmpty()) return ReplyRoute.Ignore(REASON_NOBODY_IN_FLIGHT)

        val byNumber = matchByNumber(reply, inFlight, normalizer)
        if (byNumber != null) return decide(reply, byNumber)

        val byName = namesMatching(reply, inFlight)
        return when {
            byName.size == 1 -> decide(reply, byName.first())
            byName.size > 1 -> ReplyRoute.Ignore(REASON_AMBIGUOUS_NAME)
            else -> ReplyRoute.Ignore(REASON_UNKNOWN_SENDER)
        }
    }

    private fun decide(reply: IncomingReply, phoneE164: String): ReplyRoute {
        val keyword = OptOutDetector.matchedKeyword(reply.text)
        return if (keyword != null) {
            ReplyRoute.MarkOptOut(phoneE164, keyword)
        } else {
            ReplyRoute.RecordReply(phoneE164)
        }
    }

    /** Unsaved senders arrive as a raw number — the unambiguous case. */
    private fun matchByNumber(
        reply: IncomingReply,
        inFlight: Collection<InFlightRecipient>,
        normalizer: PhoneNormalizer
    ): String? {
        val raw = reply.senderPhoneRaw ?: return null
        val normalized = normalizer.normalize(raw) as? NormalizedPhone.Valid ?: return null
        return inFlight.firstOrNull { it.phoneE164 == normalized.e164 }?.phoneE164
    }

    /**
     * Distinct numbers whose recipient carries [IncomingReply.senderName] —
     * how saved senders are matched, since their notification has no number.
     */
    private fun namesMatching(
        reply: IncomingReply,
        inFlight: Collection<InFlightRecipient>
    ): List<String> {
        val name = reply.senderName.trim()
        if (name.isEmpty()) return emptyList()
        return inFlight
            .filter { it.displayName.isNotBlank() && it.displayName.trim().equals(name, ignoreCase = true) }
            .map { it.phoneE164 }
            .distinct()
    }
}
