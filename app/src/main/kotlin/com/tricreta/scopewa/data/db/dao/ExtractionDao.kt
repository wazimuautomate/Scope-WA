package com.tricreta.scopewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tricreta.scopewa.data.db.entity.ExtractionEntity
import kotlinx.coroutines.flow.Flow

/**
 * History of group extractions — architecture doc section 5.3
 * ("group → members pulled, when").
 *
 * The history exists so a user working through 150+ groups
 * (architecture doc section 10, Q7) can see which ones they have already done
 * and what each yielded, rather than re-scrolling a 700-member group to find
 * out it produced nothing readable.
 */
@Dao
interface ExtractionDao {

    @Query("SELECT * FROM extractions ORDER BY extracted_at DESC")
    fun observeAll(): Flow<List<ExtractionEntity>>

    @Query("SELECT * FROM extractions ORDER BY extracted_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ExtractionEntity>

    @Query("SELECT * FROM extractions WHERE group_name = :groupName ORDER BY extracted_at DESC LIMIT 1")
    suspend fun mostRecentFor(groupName: String): ExtractionEntity?

    @Insert
    suspend fun insert(extraction: ExtractionEntity): Long

    @Query("DELETE FROM extractions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM extractions")
    suspend fun clear()
}
