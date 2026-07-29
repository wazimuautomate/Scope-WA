package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `campaigns` — architecture doc section 5.3: "list + template + pacing profile
 * + schedule + status".
 *
 * The recipient queue is *not* here — it is one row per person in
 * `campaign_messages`, frozen at creation time. That matters twice over: the
 * recipient-hygiene ordering from section 6 layer 3 (replied-first, then saved)
 * is decided once instead of being re-derived on every resume, and a campaign
 * that survives a reboot picks up exactly where it stopped rather than
 * reshuffling the queue underneath the user.
 *
 * Sent/failed counters are derived from `campaign_messages` rather than
 * duplicated here, so they cannot drift from the rows they describe. The one
 * exception is [sentToday] with [sentTodayEpochDay], because the daily cap in
 * section 6 layer 2 has to survive a force-stop — a cap you can reset by
 * killing the app is not a cap.
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

    /** One of [com.tricreta.scopewa.brain.campaign.PacingProfileCatalog]'s names. */
    @ColumnInfo(name = "pacing_profile")
    val pacingProfile: String = "Normal",

    /** Which WhatsApp to drive: a [com.tricreta.scopewa.accessibility.WaPackage] name. */
    @ColumnInfo(name = "wa_package")
    val waPackage: String = "Consumer",

    /** Epoch millis to start at, or null for "right now" — screenshot 09. */
    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long? = null,

    /** One of [CampaignStatus]'s names. */
    val status: String = "Draft",

    /** Set when a circuit breaker pauses the run; a `PauseReason` name. */
    @ColumnInfo(name = "pause_reason")
    val pauseReason: String? = null,

    /** Nobody hand-types blasts at 3am — section 6 layer 2. */
    @ColumnInfo(name = "active_hours_start")
    val activeHoursStart: Int = 8,

    @ColumnInfo(name = "active_hours_end")
    val activeHoursEnd: Int = 20,

    /** Days this number has been sending, 1-based. Feeds `WarmUpRamp`. */
    @ColumnInfo(name = "warm_up_day")
    val warmUpDay: Int = 1,

    /** Survives a force-stop, together with [sentTodayEpochDay]. */
    @ColumnInfo(name = "sent_today")
    val sentToday: Int = 0,

    /** Local date [sentToday] belongs to, as an epoch day. Rolls the counter over. */
    @ColumnInfo(name = "sent_today_epoch_day")
    val sentTodayEpochDay: Long = 0,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)

/**
 * Where a campaign is. Persisted as the constant's own name, so renaming one is
 * a visible migration rather than a silent data change.
 */
enum class CampaignStatus {
    /** Composed but never started. */
    Draft,

    /** Waiting for [CampaignEntity.scheduledAt]. */
    Scheduled,

    /** The foreground service is working through the queue. */
    Running,

    /** Stopped by the user, or auto-paused by a circuit breaker. Resumable. */
    Paused,

    /** Every recipient has a final outcome. */
    Completed,

    /** Stopped by the user, and not resumable. */
    Stopped;

    val isFinished: Boolean get() = this == Completed || this == Stopped

    companion object {
        fun fromName(name: String?): CampaignStatus =
            entries.firstOrNull { it.name == name } ?: Draft
    }
}
