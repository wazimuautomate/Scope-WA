package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `campaigns` — architecture doc section 5.3: "list + template + pacing profile
 * + schedule + status".
 *
 * **Scaffold only — Phase 5 owns this table.** Registered here so Phase 5 adds
 * fields rather than a new `@Database` entry (see `docs/BUILD-PLAN.md`, shared
 * hotspots). No foreign keys deliberately: Phase 5 should decide its own
 * delete semantics without inheriting a guess made in Phase 2.
 */
@Entity(tableName = "campaigns")
data class CampaignEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "",

    @ColumnInfo(name = "list_id")
    val listId: Long? = null,

    @ColumnInfo(name = "template_id")
    val templateId: Long? = null,

    /** Safe / Normal / Fast — architecture doc section 7. */
    @ColumnInfo(name = "pacing_profile")
    val pacingProfile: String = "",

    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long? = null,

    val status: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
