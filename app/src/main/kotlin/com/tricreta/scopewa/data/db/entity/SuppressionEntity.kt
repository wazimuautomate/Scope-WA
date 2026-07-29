package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `suppression_list` — "numbers he never wants contacted again", architecture
 * doc section 6 layer 3.
 *
 * Separate from `contacts.opted_out` on purpose. `opted_out` is a fact about a
 * contact we already have; this table is a number-level block that works even
 * when there is no contact row — so a suppressed number stays suppressed
 * through a delete-and-reimport, which is exactly the case where a quietly
 * resurrected STOP reply would do real damage.
 *
 * Keyed by the normalised E.164 number, not by contact id, for the same reason.
 */
@Entity(tableName = "suppression_list")
data class SuppressionEntity(
    @PrimaryKey
    @ColumnInfo(name = "phone_e164")
    val phoneE164: String,

    /** Where the block came from: "STOP reply", "Added by hand", … */
    val reason: String = "",

    @ColumnInfo(name = "added_at")
    val addedAt: Long = 0
)
