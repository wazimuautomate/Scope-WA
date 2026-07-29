package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `campaign_messages` — architecture doc section 5.3: "one row per recipient:
 * the exact text sent, status, time, error".
 *
 * Three deliberate choices:
 *
 * - **[renderedText] is stored, not a template reference.** The uniqueness
 *   meter in section 6 layer 1 and Phase 6's activity log both need to know
 *   what actually went out, spintax roll and all. A template id would only tell
 *   you what *could* have gone out.
 * - **[phoneE164] is denormalised from the contact.** The log has to stay
 *   truthful after a contact is deleted or renumbered.
 * - **[orderIndex] freezes the recipient-hygiene ordering** from section 6
 *   layer 3 at creation time, so a resume after a reboot continues the same
 *   queue rather than reshuffling it.
 */
@Entity(
    tableName = "campaign_messages",
    indices = [
        Index(value = ["campaign_id", "status"]),
        Index(value = ["campaign_id", "order_index"]),
        Index(value = ["contact_id"])
    ]
)
data class CampaignMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "campaign_id")
    val campaignId: Long = 0,

    @ColumnInfo(name = "contact_id")
    val contactId: Long = 0,

    @ColumnInfo(name = "phone_e164")
    val phoneE164: String = "",

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    /** Exactly what was sent — filled in when the message is rendered. */
    @ColumnInfo(name = "rendered_text")
    val renderedText: String = "",

    /** One of [MessageStatus]'s names. */
    val status: String = "Pending",

    /** Position in the frozen send queue; lower goes first. */
    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,

    /** Why it failed or was skipped, in words the client can act on. */
    val error: String? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0
)

/** The outcome of one recipient. Persisted as the constant's own name. */
enum class MessageStatus {
    /** Still queued. */
    Pending,

    /** Handed to WhatsApp and the compose box cleared afterwards. */
    Sent,

    /** WhatsApp was reached but the message did not go. Counts toward the
     *  consecutive-failure circuit breaker. */
    Failed,

    /** Deliberately not sent — opted out, suppressed, or inside the per-person
     *  cooldown. **Never** counts as a failure: skipping is the system working. */
    Skipped;

    val isFinal: Boolean get() = this != Pending

    companion object {
        fun fromName(name: String?): MessageStatus =
            entries.firstOrNull { it.name == name } ?: Pending
    }
}
