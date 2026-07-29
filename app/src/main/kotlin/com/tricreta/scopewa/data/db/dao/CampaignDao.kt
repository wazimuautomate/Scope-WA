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
}
