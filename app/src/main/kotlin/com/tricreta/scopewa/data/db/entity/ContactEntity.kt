package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `contacts` — architecture doc section 5.3: "number, name, source group, tags,
 * saved?, **opted out?**, last messaged, times replied".
 *
 * [phoneE164] is the identity of a contact and carries a unique index: the same
 * human written three different ways in three CSVs is one row, because
 * everything is normalised through
 * [com.tricreta.scopewa.brain.phone.PhoneNormalizer] before it gets here.
 *
 * [customFields] holds the leftover CSV columns (`town`, `last_bundle`, …) that
 * architecture doc section 6 layer 1 promises can be used as template
 * variables. Without it Phase 5 has nothing to substitute.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["phone_e164"], unique = true),
        Index(value = ["opted_out"]),
        Index(value = ["display_name"])
    ]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "phone_e164")
    val phoneE164: String,

    /** Exactly what the file said, kept so a bad normalisation is diagnosable. */
    @ColumnInfo(name = "raw_number")
    val rawNumber: String = "",

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    /** Which WhatsApp group this number was extracted from (Phase 4 fills this). */
    @ColumnInfo(name = "source_group")
    val sourceGroup: String? = null,

    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "custom_fields")
    val customFields: Map<String, String> = emptyMap(),

    /** In the phone's own address book — recipient-hygiene ordering, section 6 layer 3. */
    @ColumnInfo(name = "is_saved")
    val isSaved: Boolean = false,

    /** Replied STOP / ACHA / SITAKI, or was suppressed by hand. Never messaged again. */
    @ColumnInfo(name = "opted_out")
    val optedOut: Boolean = false,

    @ColumnInfo(name = "opted_out_at")
    val optedOutAt: Long? = null,

    @ColumnInfo(name = "opted_out_reason")
    val optedOutReason: String? = null,

    /** Drives the per-person cooldown in section 6 layer 3. */
    @ColumnInfo(name = "last_messaged_at")
    val lastMessagedAt: Long? = null,

    /** Two-way conversation is the strongest positive signal — section 6 layer 3. */
    @ColumnInfo(name = "times_replied")
    val timesReplied: Int = 0,

    /**
     * When this person last replied. Filled in by the reply listener; never
     * accompanied by the reply's text, which is not stored anywhere.
     */
    @ColumnInfo(name = "last_replied_at")
    val lastRepliedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
