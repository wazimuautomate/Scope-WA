package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `settings` — architecture doc section 5.3: "pacing profiles, active hours,
 * caps, which WhatsApp app".
 *
 * Key/value rather than one wide row: settings arrive phase by phase (Phase 1
 * adds the WhatsApp-package choice, Phase 5 the pacing profile and active
 * hours, Phase 7 the group-add caps), and a key/value table lets each add its
 * own without a schema migration or a merge conflict in this file.
 *
 * **Scaffold only — filled in alongside Phase 1's Settings screen** (see
 * `docs/BUILD-PLAN.md`).
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
