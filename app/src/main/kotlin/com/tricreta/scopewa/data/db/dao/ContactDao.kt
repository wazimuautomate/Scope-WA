package com.tricreta.scopewa.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tricreta.scopewa.data.db.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query(
        """
        SELECT * FROM contacts
        WHERE :query = ''
           OR display_name LIKE '%' || :query || '%'
           OR phone_e164 LIKE '%' || :query || '%'
        ORDER BY display_name COLLATE NOCASE, phone_e164
        """
    )
    fun observeAll(query: String): Flow<List<ContactEntity>>

    @Query(
        """
        SELECT c.* FROM contacts c
        INNER JOIN contact_list_members m ON m.contact_id = c.id
        WHERE m.list_id = :listId
          AND (:query = ''
               OR c.display_name LIKE '%' || :query || '%'
               OR c.phone_e164 LIKE '%' || :query || '%')
        ORDER BY c.display_name COLLATE NOCASE, c.phone_e164
        """
    )
    fun observeInList(listId: Long, query: String): Flow<List<ContactEntity>>

    /**
     * Contacts *not* already in [listId] — what the bulk-select picker
     * (reference screenshot 03) offers to add.
     */
    @Query(
        """
        SELECT * FROM contacts
        WHERE id NOT IN (SELECT contact_id FROM contact_list_members WHERE list_id = :listId)
          AND (:query = ''
               OR display_name LIKE '%' || :query || '%'
               OR phone_e164 LIKE '%' || :query || '%')
        ORDER BY display_name COLLATE NOCASE, phone_e164
        """
    )
    fun observeNotInList(listId: Long, query: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE opted_out = 1 ORDER BY display_name COLLATE NOCASE, phone_e164")
    fun observeOptedOut(): Flow<List<ContactEntity>>

    @Query("SELECT COUNT(*) FROM contacts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun byId(id: Long): ContactEntity?

    /** Callers must chunk: SQLite caps the number of bound variables per statement. */
    @Query("SELECT * FROM contacts WHERE phone_e164 IN (:numbers)")
    suspend fun byNumbers(numbers: List<String>): List<ContactEntity>

    @Query("SELECT phone_e164 FROM contacts WHERE opted_out = 1")
    suspend fun optedOutNumbers(): List<String>

    @Query("SELECT * FROM contacts ORDER BY display_name COLLATE NOCASE, phone_e164")
    suspend fun all(): List<ContactEntity>

    @Query(
        """
        SELECT c.* FROM contacts c
        INNER JOIN contact_list_members m ON m.contact_id = c.id
        WHERE m.list_id = :listId
        ORDER BY c.display_name COLLATE NOCASE, c.phone_e164
        """
    )
    suspend fun allInList(listId: Long): List<ContactEntity>

    /** IGNORE, not REPLACE: REPLACE deletes the old row, which would cascade
     *  list memberships away on every re-import. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contacts: List<ContactEntity>): List<Long>

    @Update
    suspend fun update(contact: ContactEntity)

    @Update
    suspend fun updateAll(contacts: List<ContactEntity>)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query(
        """
        UPDATE contacts
        SET opted_out = :optedOut,
            opted_out_at = :at,
            opted_out_reason = :reason,
            updated_at = :at
        WHERE id = :id
        """
    )
    suspend fun setOptedOut(id: Long, optedOut: Boolean, reason: String?, at: Long)

    @Query(
        """
        UPDATE contacts
        SET opted_out = 1, opted_out_at = :at, opted_out_reason = :reason, updated_at = :at
        WHERE phone_e164 = :number
        """
    )
    suspend fun optOutByNumber(number: String, reason: String?, at: Long)

    /** Feeds the per-person cooldown in architecture doc section 6 layer 3. */
    @Query("UPDATE contacts SET last_messaged_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markMessaged(id: Long, at: Long)

    /** A reply arrived — the strongest positive signal there is (section 6 layer 3). */
    @Query(
        """
        UPDATE contacts
        SET times_replied = times_replied + 1, updated_at = :at
        WHERE phone_e164 = :number
        """
    )
    suspend fun recordReply(number: String, at: Long)

    @Query(
        """
        UPDATE contacts
        SET opted_out = 0, opted_out_at = NULL, opted_out_reason = NULL, updated_at = :at
        WHERE phone_e164 = :number
        """
    )
    suspend fun clearOptOutByNumber(number: String, at: Long)
}
