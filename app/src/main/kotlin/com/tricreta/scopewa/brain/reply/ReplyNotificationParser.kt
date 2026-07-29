package com.tricreta.scopewa.brain.reply

import com.tricreta.scopewa.accessibility.WaSelectors

/**
 * The fields lifted verbatim off an Android notification, with no Android types
 * involved — so every heuristic below is testable in CI without a handset.
 *
 * `accessibility/WaNotificationListener.kt` fills this in from
 * `Notification.extras` and does nothing else; all the judgement lives here.
 */
data class RawNotification(
    val packageName: String?,
    val title: String?,
    val text: String?,
    val subText: String? = null,
    /** `FLAG_GROUP_SUMMARY` — the "3 new messages from 2 chats" roll-up. */
    val isGroupSummary: Boolean = false,
    /** `FLAG_ONGOING_EVENT` — WhatsApp's own foreground-service notice. */
    val isOngoing: Boolean = false,
    /** Priority at or below `PRIORITY_MIN`: background housekeeping, never a message. */
    val isSilent: Boolean = false,
    val postedAtMillis: Long = 0L
)

/**
 * Turns a WhatsApp notification into an [IncomingReply], or **null**.
 *
 * ## Why this is a pure function with this much doc
 *
 * It is the one piece of the reply pipeline that cannot be checked on a device
 * by a human in ten minutes — WhatsApp emits a dozen notification shapes and
 * only some of them are somebody replying. Everything ambiguous returns null:
 * a missed reply costs a reply count, whereas a mis-parsed one could attribute
 * a stranger's "stop" to a real customer and delete them from the list forever
 * (see [com.tricreta.scopewa.brain.campaign.OptOutDetector]'s doc on which
 * direction of error hurts more — the answer is *both*, differently).
 *
 * ## What gets rejected, and why
 *
 * - **Anything that isn't WhatsApp or WhatsApp Business.** Notification access
 *   is a phone-wide permission; this app looks at two packages.
 * - **Group summaries.** WhatsApp posts a roll-up ("3 new messages from 2
 *   chats") alongside the per-chat notifications. Parsing it would invent a
 *   sender called "WhatsApp".
 * - **Ongoing and silent notifications.** "Checking for new messages" is a
 *   foreground-service notice, not a message.
 * - **Typing indicators, call notices, backup progress.** Wording lives in
 *   `WaSelectors` with the rest of WhatsApp's strings.
 * - **Anything with an empty title or body.**
 *
 * ## Group messages
 *
 * WhatsApp uses two shapes across versions: title `Group: Sender` with a plain
 * body, and title `Group` with body `Sender: message`. Both are recognised,
 * the `Sender:` prefix is stripped, and the result is flagged
 * [IncomingReply.isGroupMessage] — which [ReplyRouter] then ignores, because a
 * message in a group is not a reply to a one-to-one campaign and the
 * notification carries no number to attribute it to anyway.
 *
 * ## No regex on purpose
 *
 * Android's ICU regex engine is stricter than the JVM's and CI cannot catch the
 * difference (see `MEMORY.md`). Everything here is plain string work.
 *
 * Pure Kotlin — no Android imports. It reads WhatsApp's wording from
 * `WaSelectors`, which is likewise Android-free by design, so the "all
 * WhatsApp strings in one file" rule in `CLAUDE.md` holds without dragging a
 * `Context` into `brain/`.
 */
object ReplyNotificationParser {

    /** `Group name: Sender` in a notification title. */
    private const val TITLE_SEPARATOR = ": "

    /** Longer than this and the text before a colon is a sentence, not a name. */
    const val MAX_SENDER_PREFIX_CHARS = 40

    /** Fewer digits than this and it is a nickname with numbers in it. */
    private const val MIN_PHONE_DIGITS = 7

    /** Characters allowed alongside digits in something claiming to be a number. */
    private val PHONE_PUNCTUATION = setOf('+', '-', '(', ')', '.', '/')

    /** WhatsApp prefixes unsaved group participants with a tilde. */
    private const val UNSAVED_MARKER = '~'

    /** A count-style summary is at most this many words ("3 new messages from 2 chats"). */
    private const val MAX_SUMMARY_WORDS = 6

    fun parse(raw: RawNotification): IncomingReply? {
        if (!WaSelectors.isSupportedPackage(raw.packageName)) return null
        if (raw.isGroupSummary || raw.isOngoing || raw.isSilent) return null

        val title = raw.title?.trim().orEmpty()
        val body = raw.text?.trim().orEmpty()
        if (title.isEmpty() || body.isEmpty()) return null

        // A notification titled "WhatsApp" is the app talking about itself.
        if (isAppTitle(title)) return null
        if (WaSelectors.matchFragment(body, WaSelectors.NOTIFICATION_NOISE_FRAGMENTS) != null) return null
        if (looksLikeMessageCount(body)) return null

        val titleGroup = if (title.contains(TITLE_SEPARATOR)) {
            title.substringBefore(TITLE_SEPARATOR).trim().ifEmpty { null }
        } else {
            null
        }
        val titleSender = if (titleGroup != null) title.substringAfter(TITLE_SEPARATOR).trim() else title

        // "Sender: message" — only meaningful when the prefix is short enough to
        // be a name and there is something left over after it.
        val prefix = body.substringBefore(':', missingDelimiterValue = "").trim()
        val hasSenderPrefix = prefix.isNotEmpty() &&
            prefix.length <= MAX_SENDER_PREFIX_CHARS &&
            !prefix.contains('\n') &&
            body.substringAfter(':').isNotBlank()

        // subText is only trusted as a group name when the body also carries a
        // sender prefix. On its own it is set for all sorts of things (multiple
        // accounts, channel names) and treating a one-to-one chat as a group
        // would silently throw the reply away.
        val subGroup = raw.subText?.trim()
            ?.takeIf { it.isNotEmpty() && !isAppTitle(it) && !it.equals(title, ignoreCase = true) }
        val groupName = titleGroup ?: subGroup?.takeIf { hasSenderPrefix }

        var sender = titleSender
        var messageText = body
        if (groupName != null && hasSenderPrefix) {
            sender = prefix
            messageText = body.substringAfter(':').trim()
        }

        sender = sender.trimStart(UNSAVED_MARKER).trim()
        if (sender.isEmpty() || messageText.isEmpty()) return null
        if (isAppTitle(sender)) return null

        return IncomingReply(
            senderName = sender,
            senderPhoneRaw = sender.takeIf { looksLikePhoneNumber(it) },
            text = messageText,
            timestampMillis = raw.postedAtMillis,
            isGroupMessage = groupName != null,
            groupName = groupName
        )
    }

    private fun isAppTitle(value: String): Boolean =
        value.trim().lowercase() in WaSelectors.NOTIFICATION_APP_TITLES

    /**
     * "3 new messages", "2 messages from 2 chats" — the roll-up shapes that
     * survive when a device does not set `FLAG_GROUP_SUMMARY` (some OEM builds
     * don't). Requires a leading digit *and* a message/chat noun, so an actual
     * reply like "5 bundles please" is untouched.
     */
    private fun looksLikeMessageCount(text: String): Boolean {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > MAX_SUMMARY_WORDS) return false
        if (!words.first().all { it.isDigit() }) return false
        return words.any { it.startsWith("message") || it.startsWith("chat") }
    }

    /**
     * Whether a notification title is the raw number of an unsaved contact
     * rather than a name. Deliberately strict — everything that is not clearly
     * a number is matched by name instead.
     */
    private fun looksLikePhoneNumber(value: String): Boolean {
        if (value.count { it.isDigit() } < MIN_PHONE_DIGITS) return false
        return value.all { it.isDigit() || it.isWhitespace() || it in PHONE_PUNCTUATION }
    }
}
