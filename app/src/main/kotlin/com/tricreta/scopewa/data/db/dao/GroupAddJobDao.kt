package com.tricreta.scopewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupAddJobDao {

    @Query("SELECT * FROM group_add_jobs ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<GroupAddJobEntity>>

    @Query("SELECT * FROM group_add_jobs WHERE id = :id")
    fun observeById(id: Long): Flow<GroupAddJobEntity?>

    @Query("SELECT * FROM group_add_jobs WHERE id = :id")
    suspend fun byId(id: Long): GroupAddJobEntity?

    /** The one job the foreground service should be working on. */
    @Query("SELECT * FROM group_add_jobs WHERE status = 'Running' ORDER BY updated_at DESC LIMIT 1")
    suspend fun activeJob(): GroupAddJobEntity?

    @Query("SELECT * FROM group_add_jobs WHERE status = 'Running' ORDER BY updated_at DESC LIMIT 1")
    fun observeActiveJob(): Flow<GroupAddJobEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: GroupAddJobEntity): Long

    @Update
    suspend fun update(job: GroupAddJobEntity)

    @Query("DELETE FROM group_add_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE group_add_jobs SET status = :status, stop_reason = :stopReason, updated_at = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, stopReason: String?, at: Long)

    @Query("UPDATE group_add_jobs SET started_at = :at, updated_at = :at WHERE id = :id AND started_at IS NULL")
    suspend fun markStarted(id: Long, at: Long)

    @Query("UPDATE group_add_jobs SET finished_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markFinished(id: Long, at: Long)

    /**
     * Adds made on a given local date across **every** job.
     *
     * This is the query the daily cap of 20 is enforced against. Architecture
     * doc section 6 layer 5 caps the phone number, not the job — three jobs in
     * one day must not each get a fresh allowance, exactly as the send-side cap
     * works (`MEMORY.md`, "the daily cap belongs to the phone number").
     */
    @Query("SELECT COALESCE(SUM(added_today), 0) FROM group_add_jobs WHERE day_stamp = :dayStamp")
    suspend fun addedOn(dayStamp: String): Int

    @Query("SELECT COALESCE(SUM(added_today), 0) FROM group_add_jobs WHERE day_stamp = :dayStamp")
    fun observeAddedOn(dayStamp: String): Flow<Int>
}
