package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `campaign_messages` — architecture doc section 5.3: "one row per recipient:
 * the exact text sent, status, time, error".
 *
 * Storing the *rendered* text, not a template reference, is the point: the
 * uniqueness meter in section 6 layer 1 and Phase 6's activity log both need to
 * know what actually went out, spintax roll and all.
 *
 * **Scaffold only — Phase 5 owns this table** (see `docs/BUILD-PLAN.md`).
 */
@Entity(
    tableName = "campaign_messages",
    indices = [Index(value = ["campaign_id"]), Index(value = ["contact_id"])]
)
data class CampaignMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "campaign_id")
    val campaignId: Long = 0,

    @ColumnInfo(name = "contact_id")
    val contactId: Long = 0,

    @ColumnInfo(name = "rendered_text")
    val renderedText: String = "",

    val status: String = "",

    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,

    val error: String? = null
)
