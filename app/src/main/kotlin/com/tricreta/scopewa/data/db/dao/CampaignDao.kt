package com.tricreta.scopewa.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import kotlinx.coroutines.flow.Flow

/** Live counters for one campaign, derived from its message rows. */
data class CampaignProgress(
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "sent") val sent: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    @ColumnInfo(name = "skipped") val skipped: Int,
    @ColumnInfo(name = "pending") val pending: Int
)

/**
 * One row of Phase 6's activity log: a finished message joined to the campaign
 * it belonged to, so the log can be read without a second query per row.
 *
 * The join is a `LEFT JOIN` and [campaignName] is nullable-safe on purpose —
 * a message outlives its campaign row being deleted, and the log going blank
 * because a campaign was tidied up would defeat the point of keeping it.
 */
data class ActivityLogEntry(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "campaign_id") val campaignId: Long,
    @ColumnInfo(name = "campaign_name") val campaignName: String?,
    @ColumnInfo(name = "phone_e164") val phoneE164: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "rendered_text") val renderedText: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long?,
    @ColumnInfo(name = "error") val error: String?
)

/** Per-campaign totals, so a list of campaigns needs one query rather than N. */
data class CampaignTotals(
    @ColumnInfo(name = "campaign_id") val campaignId: Long,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "sent") val sent: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    @ColumnInfo(name = "skipped") val skipped: Int
)

/** Sentinel for "don't filter by campaign" — Room can't bind a null Long here. */
const val ANY_CAMPAIGN: Long = -1L

/**
 * Sentinel for "don't filter by status". Not a `MessageStatus` name, so it can
 * never collide with a real one.
 */
const val ANY_STATUS: String = "*"

/**
 * One person a campaign has already messaged — just enough to match an
 * incoming reply back to them. See [CampaignDao.messagedSince].
 */
data class MessagedRecipient(
    @ColumnInfo(name = "phone_e164") val phoneE164: String,
    @ColumnInfo(name = "display_name") val displayName: String
)

@Dao
interface CampaignDao {

    @Query("SELECT * FROM campaigns ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<CampaignEntity>>

    @Query("SELECT * FROM campaigns WHERE id = :id")
    fun observeById(id: Long): Flow<CampaignEntity?>

    @Query("SELECT * FROM campaigns WHERE id = :id")
    suspend fun byId(id: Long): CampaignEntity?

    /**
     * The one campaign the foreground service should be working on. Scheduled
     * campaigns count: the service is what notices their start time has passed.
     */
    @Query("SELECT * FROM campaigns WHERE status IN ('Running', 'Scheduled') ORDER BY updated_at DESC LIMIT 1")
    suspend fun activeCampaign(): CampaignEntity?

    @Query("SELECT * FROM campaigns WHERE status IN ('Running', 'Scheduled') ORDER BY updated_at DESC LIMIT 1")
    fun observeActiveCampaign(): Flow<CampaignEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(campaign: CampaignEntity): Long

    @Update
    suspend fun update(campaign: CampaignEntity)

    @Query("DELETE FROM campaigns WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE campaigns SET status = :status, pause_reason = :pauseReason, updated_at = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, pauseReason: String?, at: Long)

    @Query(
        """
        UPDATE campaigns
        SET sent_today = :sentToday, sent_today_epoch_day = :epochDay, updated_at = :at
        WHERE id = :id
        """
    )
    suspend fun setDailyCounter(id: Long, sentToday: Int, epochDay: Long, at: Long)

    @Query("UPDATE campaigns SET started_at = :at, updated_at = :at WHERE id = :id AND started_at IS NULL")
    suspend fun markStarted(id: Long, at: Long)

    @Query("UPDATE campaigns SET finished_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markFinished(id: Long, at: Long)

    // ---- the queue ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<CampaignMessageEntity>)

    @Update
    suspend fun updateMessage(message: CampaignMessageEntity)

    /** The next person to message. Ordering was frozen when the queue was built. */
    @Query(
        """
        SELECT * FROM campaign_messages
        WHERE campaign_id = :campaignId AND status = 'Pending'
        ORDER BY order_index
        LIMIT 1
        """
    )
    suspend fun nextPending(campaignId: Long): CampaignMessageEntity?

    @Query("SELECT COUNT(*) FROM campaign_messages WHERE campaign_id = :campaignId AND status = 'Pending'")
    suspend fun pendingCount(campaignId: Long): Int

    @Query(
        """
        SELECT * FROM campaign_messages
        WHERE campaign_id = :campaignId
        ORDER BY
            CASE status WHEN 'Pending' THEN 0 ELSE 1 END,
            order_index
        """
    )
    fun observeMessages(campaignId: Long): Flow<List<CampaignMessageEntity>>

    @Query("SELECT * FROM campaign_messages WHERE campaign_id = :campaignId ORDER BY order_index")
    suspend fun allMessages(campaignId: Long): List<CampaignMessageEntity>

    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(status = 'Sent'), 0) AS sent,
            COALESCE(SUM(status = 'Failed'), 0) AS failed,
            COALESCE(SUM(status = 'Skipped'), 0) AS skipped,
            COALESCE(SUM(status = 'Pending'), 0) AS pending
        FROM campaign_messages
        WHERE campaign_id = :campaignId
        """
    )
    fun observeProgress(campaignId: Long): Flow<CampaignProgress>

    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(status = 'Sent'), 0) AS sent,
            COALESCE(SUM(status = 'Failed'), 0) AS failed,
            COALESCE(SUM(status = 'Skipped'), 0) AS skipped,
            COALESCE(SUM(status = 'Pending'), 0) AS pending
        FROM campaign_messages
        WHERE campaign_id = :campaignId
        """
    )
    suspend fun progress(campaignId: Long): CampaignProgress

    /** Every message text already committed to this campaign — feeds the uniqueness meter. */
    @Query("SELECT rendered_text FROM campaign_messages WHERE campaign_id = :campaignId AND rendered_text <> ''")
    suspend fun renderedTexts(campaignId: Long): List<String>

    /**
     * Total sent by *any* campaign on a given local date. The daily cap in
     * section 6 layer 2 is a property of the phone number, not of one campaign
     * — three campaigns in one day must not each get a fresh allowance.
     */
    @Query(
        """
        SELECT COUNT(*) FROM campaign_messages
        WHERE status = 'Sent' AND sent_at >= :startOfDayMillis AND sent_at < :endOfDayMillis
        """
    )
    suspend fun sentBetween(startOfDayMillis: Long, endOfDayMillis: Long): Int

    /**
     * An opt-out is permanent and global: drop that number from every queue it
     * is still sitting in, not only the campaign that provoked the reply.
     */
    @Query(
        """
        UPDATE campaign_messages
        SET status = 'Skipped', error = :reason
        WHERE phone_e164 = :number AND status = 'Pending'
        """
    )
    suspend fun skipPendingForNumber(number: String, reason: String)

    /** Epoch millis of the very first send, ever. Anchors the warm-up ramp. */
    @Query("SELECT MIN(sent_at) FROM campaign_messages WHERE status = 'Sent'")
    suspend fun firstSendAt(): Long?

    // ---- Phase 6: the activity log ----------------------------------------

    /**
     * Every message that has an outcome, newest first, across every campaign.
     *
     * `Pending` rows are excluded deliberately: the activity log answers "what
     * happened", and a queued message has not happened yet. The Running screen
     * is where the queue is visible.
     *
     * Pass [ANY_CAMPAIGN] / [ANY_STATUS] to skip a filter. Ordering falls back
     * to the row id because a skipped message never gets a `sent_at` — without
     * that tiebreak, every skip would pile up at the bottom in insertion order
     * regardless of when the campaign ran.
     */
    @Query(
        """
        SELECT
            m.id AS id,
            m.campaign_id AS campaign_id,
            c.name AS campaign_name,
            m.phone_e164 AS phone_e164,
            m.display_name AS display_name,
            m.rendered_text AS rendered_text,
            m.status AS status,
            m.sent_at AS sent_at,
            m.error AS error
        FROM campaign_messages AS m
        LEFT JOIN campaigns AS c ON c.id = m.campaign_id
        WHERE m.status <> 'Pending'
            AND (:campaignId = -1 OR m.campaign_id = :campaignId)
            AND (:status = '*' OR m.status = :status)
        ORDER BY COALESCE(m.sent_at, 0) DESC, m.id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observeActivityLog(
        campaignId: Long,
        status: String,
        limit: Int,
        offset: Int
    ): Flow<List<ActivityLogEntry>>

    /** The same rows, fetched once — what the CSV export reads. */
    @Query(
        """
        SELECT
            m.id AS id,
            m.campaign_id AS campaign_id,
            c.name AS campaign_name,
            m.phone_e164 AS phone_e164,
            m.display_name AS display_name,
            m.rendered_text AS rendered_text,
            m.status AS status,
            m.sent_at AS sent_at,
            m.error AS error
        FROM campaign_messages AS m
        LEFT JOIN campaigns AS c ON c.id = m.campaign_id
        WHERE m.status <> 'Pending'
            AND (:campaignId = -1 OR m.campaign_id = :campaignId)
            AND (:status = '*' OR m.status = :status)
        ORDER BY COALESCE(m.sent_at, 0) DESC, m.id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun activityLog(
        campaignId: Long,
        status: String,
        limit: Int,
        offset: Int
    ): List<ActivityLogEntry>

    /** How many rows the current filter would return — drives "load more". */
    @Query(
        """
        SELECT COUNT(*) FROM campaign_messages
        WHERE status <> 'Pending'
            AND (:campaignId = -1 OR campaign_id = :campaignId)
            AND (:status = '*' OR status = :status)
        """
    )
    fun observeActivityLogCount(campaignId: Long, status: String): Flow<Int>

    /** Totals for every campaign at once, so the reports list needs one query. */
    @Query(
        """
        SELECT
            campaign_id AS campaign_id,
            COUNT(*) AS total,
            COALESCE(SUM(status = 'Sent'), 0) AS sent,
            COALESCE(SUM(status = 'Failed'), 0) AS failed,
            COALESCE(SUM(status = 'Skipped'), 0) AS skipped
        FROM campaign_messages
        GROUP BY campaign_id
        """
    )
    fun observeCampaignTotals(): Flow<List<CampaignTotals>>

    // ---- replies -----------------------------------------------------------

    /**
     * Everyone messaged since [since], across every campaign — the set a reply
     * could plausibly belong to. Campaign-agnostic on purpose: somebody
     * answering yesterday's campaign today is still answering *us*, and an
     * opt-out is global anyway.
     */
    @Query(
        """
        SELECT DISTINCT phone_e164, display_name FROM campaign_messages
        WHERE status = 'Sent' AND sent_at IS NOT NULL AND sent_at >= :since
        """
    )
    suspend fun messagedSince(since: Long): List<MessagedRecipient>

    /**
     * Records a reply against the *most recent* message sent to that number,
     * rather than every message it ever received. Without the subquery a single
     * reply would light up months of history and the cold-batch breaker would
     * never fire again.
     */
    @Query(
        """
        UPDATE campaign_messages
        SET replied_at = :at, reply_count = reply_count + 1
        WHERE id = (
            SELECT id FROM campaign_messages
            WHERE phone_e164 = :number AND status = 'Sent'
            ORDER BY sent_at DESC
            LIMIT 1
        )
        """
    )
    suspend fun recordReplyForNumber(number: String, at: Long)

    /**
     * How many people have answered this campaign since [since]. Feeds
     * [com.tricreta.scopewa.brain.safety.CampaignSafetyState.repliesInCurrentBatch]
     * — the number that was structurally always zero until the reply listener
     * existed.
     */
    @Query(
        """
        SELECT COUNT(*) FROM campaign_messages
        WHERE campaign_id = :campaignId AND replied_at IS NOT NULL AND replied_at >= :since
        """
    )
    suspend fun replyCountSince(campaignId: Long, since: Long): Int
}
