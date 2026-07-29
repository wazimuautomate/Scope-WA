package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of one group-add job. Stored as a name, not an ordinal. */
enum class GroupAddStatus {
    Draft,
    Running,
    Paused,
    Finished,
    Stopped;

    val isFinished: Boolean get() = this == Finished || this == Stopped

    companion object {
        fun fromName(value: String?): GroupAddStatus =
            entries.firstOrNull { it.name == value } ?: Draft
    }
}

/**
 * `group_add_jobs` — architecture doc section 5.3: "target group, source list,
 * daily counter that survives restarts". Phase 7 owns this table.
 *
 * ## Why the per-person results live in this row
 *
 * `docs/BUILD-PLAN.md`'s shared-hotspots rule is that later phases add *fields
 * and DAOs to their own entity*, never new `@Database(entities = [...])`
 * entries. There is no `group_add_targets` table and Phase 7 is not allowed to
 * invent one, so the queue and the four result buckets are stored here as
 * encoded collections via [com.tricreta.scopewa.data.db.Converters].
 *
 * That is a real trade-off and worth naming: it cannot be queried per person,
 * and a job with thousands of candidates would be a fat row. It is acceptable
 * because the daily cap is **20** — a job is at most a few dozen people, and
 * anything beyond that is deferred to tomorrow rather than stored.
 *
 * ## Why the daily counter is persisted
 *
 * The cap in section 6 layer 5 has to survive the app being killed. A cap you
 * can reset by force-stopping the app is not a cap. [addedToday] is scoped by
 * [dayStamp], and the phone-wide total is the sum across every job sharing a
 * day stamp — the cap belongs to the number, not to one job.
 */
@Entity(
    tableName = "group_add_jobs",
    indices = [Index(value = ["day_stamp"]), Index(value = ["status"])]
)
data class GroupAddJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The group as WhatsApp shows it — this is what gets searched for on screen. */
    @ColumnInfo(name = "target_group")
    val targetGroup: String = "",

    /** Contact list the candidates came from, when there was one. */
    @ColumnInfo(name = "source_list_id")
    val sourceListId: Long? = null,

    /** Which WhatsApp variant to drive, e.g. `com.whatsapp`. */
    @ColumnInfo(name = "wa_package")
    val waPackage: String = "",

    val status: String = GroupAddStatus.Draft.name,

    /** Why it paused or stopped — a [com.tricreta.scopewa.brain.groupadd.GroupAddStopReason]
     *  name, or free text for a user-pressed pause. */
    @ColumnInfo(name = "stop_reason")
    val stopReason: String? = null,

    // ---- the queue and the buckets -----------------------------------------

    /** E.164 numbers still to attempt, in planned order. */
    val pending: List<String> = emptyList(),

    /** number → display name, so a results row never reads as a bare number. */
    val names: Map<String, String> = emptyMap(),

    /** number → [com.tricreta.scopewa.brain.groupadd.AddProvenance] label. The
     *  audit trail for "why was this person offered at all". */
    val provenance: Map<String, String> = emptyMap(),

    /** Confirmed in the group. */
    val added: List<String> = emptyList(),

    /**
     * Privacy-blocked. **Terminal — never retried**, per architecture doc
     * section 8. The UI offers the invite link for these instead.
     */
    @ColumnInfo(name = "needs_invite")
    val needsInvite: List<String> = emptyList(),

    /** number → failure reason. */
    val failed: Map<String, String> = emptyMap(),

    /** number → why it was skipped. A skip is never a failure. */
    val skipped: Map<String, String> = emptyMap(),

    /** number → why it was rejected as cold, kept so the safety screen is auditable. */
    @ColumnInfo(name = "rejected_cold")
    val rejectedCold: Map<String, String> = emptyMap(),

    // ---- counters ----------------------------------------------------------

    @ColumnInfo(name = "added_count")
    val addedCount: Int = 0,

    @ColumnInfo(name = "failed_count")
    val failedCount: Int = 0,

    @ColumnInfo(name = "needs_invite_count")
    val needsInviteCount: Int = 0,

    @ColumnInfo(name = "skipped_count")
    val skippedCount: Int = 0,

    @ColumnInfo(name = "skipped_cold_count")
    val skippedColdCount: Int = 0,

    /** Adds completed on [dayStamp]; reset when the day rolls over. */
    @ColumnInfo(name = "added_today")
    val addedToday: Int = 0,

    /** Local date the counter belongs to, as `yyyy-MM-dd`. */
    @ColumnInfo(name = "day_stamp")
    val dayStamp: String = "",

    @ColumnInfo(name = "last_failure")
    val lastFailure: String? = null,

    // ---- timestamps --------------------------------------------------------

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
) {
    val totalHandled: Int get() = addedCount + failedCount + needsInviteCount + skippedCount

    val totalPlanned: Int get() = totalHandled + pending.size

    /** Display name for a number, falling back to the number itself. */
    fun labelFor(number: String): String = names[number]?.takeIf { it.isNotBlank() } ?: number
}
