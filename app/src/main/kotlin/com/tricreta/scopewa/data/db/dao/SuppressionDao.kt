package com.tricreta.scopewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tricreta.scopewa.data.db.entity.SuppressionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SuppressionDao {

    @Query("SELECT * FROM suppression_list ORDER BY added_at DESC")
    fun observeAll(): Flow<List<SuppressionEntity>>

    @Query("SELECT phone_e164 FROM suppression_list")
    suspend fun allNumbers(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: SuppressionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(entries: List<SuppressionEntity>)

    @Query("DELETE FROM suppression_list WHERE phone_e164 = :number")
    suspend fun remove(number: String)
}
