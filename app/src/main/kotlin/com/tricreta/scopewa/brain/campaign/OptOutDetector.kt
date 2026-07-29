package com.tricreta.scopewa.brain.campaign

/**
 * Recognises an opt-out reply — architecture doc section 6 layer 3: "Anyone who
 * replies STOP/ACHA/SITAKI is permanently excluded. Automatically."
 *
 * Two of those three are Swahili ("acha" = stop it, "sitaki" = I don't want),
 * which is why this can't be a plain English stop-word list. The client's own
 * message template ends "Reply STOP to never receive this", so the promise is
 * already made to recipients — this is what keeps it.
 *
 * ## Why the matching is shaped the way it is
 *
 * Both directions of error hurt, and it is worth being clear about which hurts
 * more. A **false positive** silently removes a real customer forever. A
 * **false negative** keeps messaging someone who asked you to stop — and
 * architecture doc section 2 names "Block rate" and "Report rate" as the
 * biggest ban signals WhatsApp has, well ahead of anything the pacing code can
 * influence. So a missed opt-out is not the safe direction; it is the one that
 * actually gets the number banned.
 *
 * The compromise: match on whole words only, allow a short sentence rather than
 * just a bare keyword, and refuse anything that reads like ordinary
 * conversation. "stopped" never matches "stop", the Swahili name "Achieng"
 * never matches "acha", "don't stop" is not an opt-out, and neither is "can you
 * stop by tomorrow".
 *
 * ## What feeds this
 *
 * Replies are observed by
 * [com.tricreta.scopewa.accessibility.WaNotificationListener], parsed by
 * [com.tricreta.scopewa.brain.reply.ReplyNotificationParser] and attributed by
 * [com.tricreta.scopewa.brain.reply.ReplyRouter], which calls this. The
 * hand-marking path in the contacts screen still exists and still matters:
 * notification access is optional, and on a phone that declined it this
 * detector is only exercised when a reply is fed to it by hand.
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
object OptOutDetector {

    /** Lowercase, in the order they're checked. */
    val KEYWORDS: List<String> = listOf("stop", "acha", "sitaki", "unsubscribe", "toa")

    /**
     * Longer than this and it's a conversation, not an instruction. Generous
     * enough for "please stop sending me these messages".
     */
    const val MAX_WORDS = 8

    /** Straight and curly, so a phone keyboard's autocorrect doesn't defeat the guard. */
    private val APOSTROPHES = setOf('\'', '’', 'ʼ')

    /** "don't stop" is the opposite of an opt-out. */
    private val NEGATIONS = setOf("dont", "don", "do", "not", "never", "no", "usiache")

    /** "stop by", "stop at" — an arrangement to meet, not an unsubscribe. */
    private val PHRASE_CONTINUATIONS = setOf("by", "at", "over", "there", "here")

    fun isOptOut(replyText: String?): Boolean = matchedKeyword(replyText) != null

    /**
     * @return the keyword that matched, lowercase, or null when the reply is
     *         ordinary conversation.
     */
    fun matchedKeyword(replyText: String?): String? {
        if (replyText.isNullOrBlank()) return null

        val words = replyText
            .lowercase()
            // Apostrophes are dropped rather than treated as separators, so
            // "don't" stays one word and the negation guard below can see it.
            // Splitting it into "don" + "t" would put "t" next to the keyword
            // and let "don't stop" through as an opt-out.
            .filterNot { it in APOSTROPHES }
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

        if (words.isEmpty() || words.size > MAX_WORDS) return null

        val index = words.indexOfFirst { it in KEYWORDS }
        if (index < 0) return null

        if (words.getOrNull(index - 1) in NEGATIONS) return null
        if (words.getOrNull(index + 1) in PHRASE_CONTINUATIONS) return null

        return words[index]
    }

    /** What gets written to `contacts.opted_out_reason`. */
    fun reasonFor(keyword: String): String = "Replied \"${keyword.uppercase()}\""
}
