package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `group_add_jobs` — architecture doc section 5.3: "target group, source list,
 * daily counter that survives restarts".
 *
 * The daily counter is persisted rather than held in memory because the daily
 * cap in section 6 layer 5 has to survive the app being killed — a cap you can
 * reset by force-stopping the app is not a cap.
 *
 * **Scaffold only — Phase 7 owns this table** (see `docs/BUILD-PLAN.md`).
 * Phase 7 ships last, deliberately.
 */
@Entity(tableName = "group_add_jobs")
data class GroupAddJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "target_group")
    val targetGroup: String = "",

    @ColumnInfo(name = "source_list_id")
    val sourceListId: Long? = null,

    /** Adds completed on [dayStamp]; reset when the day rolls over. */
    @ColumnInfo(name = "added_today")
    val addedToday: Int = 0,

    /** Local date the counter belongs to, as `yyyy-MM-dd`. */
    @ColumnInfo(name = "day_stamp")
    val dayStamp: String = "",

    val status: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
