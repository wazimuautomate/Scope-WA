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
 * **Scaffold only — Phase 4 owns this table** (see `docs/BUILD-PLAN.md`).
 */
@Entity(tableName = "extractions")
data class ExtractionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "group_name")
    val groupName: String = "",

    @ColumnInfo(name = "member_count")
    val memberCount: Int = 0,

    /** Members whose number WhatsApp wouldn't show. */
    @ColumnInfo(name = "hidden_count")
    val hiddenCount: Int = 0,

    @ColumnInfo(name = "extracted_at")
    val extractedAt: Long = 0
)
