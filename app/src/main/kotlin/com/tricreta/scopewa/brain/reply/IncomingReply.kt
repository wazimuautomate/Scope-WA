package com.tricreta.scopewa.brain.reply

/**
 * One WhatsApp message that arrived on this phone, reduced to the handful of
 * fields opt-out handling actually needs — architecture doc section 6 layer 3.
 *
 * ## What is deliberately *not* here
 *
 * Reading notifications is a sensitive permission and the app must never log,
 * export or persist message bodies. [text] exists only long enough for
 * [com.tricreta.scopewa.brain.campaign.OptOutDetector] to decide whether it is
 * an opt-out; nothing downstream writes it to the database or to logcat. What
 * gets stored is a reply *count*, a timestamp, and — for an opt-out — the
 * single matched keyword.
 *
 * ## Why the phone number is nullable
 *
 * A WhatsApp notification carries whatever the phone's own address book calls
 * the sender. For a saved contact that is a name with no number anywhere in the
 * notification; only for an unsaved contact is the title the raw number. So
 * matching a reply back to a campaign recipient is name-based about half the
 * time, which is exactly why [ReplyRouter] refuses to guess when a name is
 * ambiguous.
 *
 * Pure Kotlin — no Android imports — so CI tests it without a phone.
 */
data class IncomingReply(
    /** Display name as WhatsApp showed it, or the raw number for unsaved senders. */
    val senderName: String,

    /** Set only when [senderName] is itself a phone number. Not normalised yet. */
    val senderPhoneRaw: String?,

    /** The message body. Never persisted — see the class doc. */
    val text: String,

    /** `SystemClock`-independent wall time the notification was posted. */
    val timestampMillis: Long,

    /** True when this arrived in a group rather than a one-to-one chat. */
    val isGroupMessage: Boolean,

    /** The group's name when [isGroupMessage]; null otherwise. */
    val groupName: String? = null
)
