package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `extractions` — architecture doc section 5.3: "group → members pulled, when".
 *
 * [hiddenCount] is here from the start because of WhatsApp's LID rollout
 * (architecture doc section 3.2 point 2, section 8): some members' numbers
 * simply cannot be read, and the honest thing is to record how many rather than
 * let a 700-member group quietly export as 400.
 *
 * Filled in by Phase 4.
 */
@Entity(tableName = "extractions")
data class ExtractionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "group_name")
    val groupName: String = "",

    /** Participant rows actually read off the screen. */
    @ColumnInfo(name = "member_count")
    val memberCount: Int = 0,

    /**
     * Rows read whose number could not be recovered — the member is saved in
     * the phonebook (WhatsApp renders the saved name) or WhatsApp is hiding it
     * behind a LID. See
     * [com.tricreta.scopewa.data.repository.extract.MemberNumberStatus] for why
     * those two are indistinguishable from a rendered screen.
     */
    @ColumnInfo(name = "hidden_count")
    val hiddenCount: Int = 0,

    /**
     * What WhatsApp's own "N participants" header claimed, when readable.
     *
     * Stored because it is the only way to detect a scroll that stopped early:
     * if this exceeds [memberCount], the extraction was incomplete. A partial
     * result that presents itself as complete is the worst failure here, since
     * the user acts on it.
     */
    @ColumnInfo(name = "reported_member_count")
    val reportedMemberCount: Int? = null,

    /** Contacts newly written to the database by this extraction. */
    @ColumnInfo(name = "imported_count")
    val importedCount: Int = 0,

    @ColumnInfo(name = "extracted_at")
    val extractedAt: Long = 0
) {
    /** True when WhatsApp reported more members than were read. */
    val looksIncomplete: Boolean
        get() = reportedMemberCount != null && memberCount < reportedMemberCount
}
