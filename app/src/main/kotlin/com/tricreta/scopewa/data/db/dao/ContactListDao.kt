package com.tricreta.scopewa.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tricreta.scopewa.data.db.entity.ContactListEntity
import com.tricreta.scopewa.data.db.entity.ContactListMemberEntity
import kotlinx.coroutines.flow.Flow

/** A list plus its member count — "Fifth 824 · 824 contacts", screenshot 02. */
data class ContactListSummary(
    val id: Long,
    val name: String,
    val purpose: String,
    @ColumnInfo(name = "contact_count") val contactCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface ContactListDao {

    @Query(
        """
        SELECT l.id AS id,
               l.name AS name,
               l.purpose AS purpose,
               l.updated_at AS updated_at,
               COUNT(m.contact_id) AS contact_count
        FROM contact_lists l
        LEFT JOIN contact_list_members m ON m.list_id = l.id
        GROUP BY l.id
        ORDER BY l.name COLLATE NOCASE
        """
    )
    fun observeSummaries(): Flow<List<ContactListSummary>>

    @Query(
        """
        SELECT l.id AS id,
               l.name AS name,
               l.purpose AS purpose,
               l.updated_at AS updated_at,
               COUNT(m.contact_id) AS contact_count
        FROM contact_lists l
        LEFT JOIN contact_list_members m ON m.list_id = l.id
        WHERE l.id = :id
        GROUP BY l.id
        """
    )
    fun observeSummary(id: Long): Flow<ContactListSummary?>

    @Query("SELECT * FROM contact_lists WHERE id = :id")
    suspend fun byId(id: Long): ContactListEntity?

    @Query("SELECT * FROM contact_lists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): ContactListEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: ContactListEntity): Long

    @Query("UPDATE contact_lists SET name = :name, purpose = :purpose, updated_at = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, purpose: String, at: Long)

    /** Memberships cascade; the contacts themselves survive. */
    @Query("DELETE FROM contact_lists WHERE id = :id")
    suspend fun deleteList(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<ContactListMemberEntity>)

    @Query("DELETE FROM contact_list_members WHERE list_id = :listId AND contact_id IN (:contactIds)")
    suspend fun removeMembers(listId: Long, contactIds: List<Long>)

    /** Which lists a contact belongs to — used when exporting the `Lists` column. */
    @Query(
        """
        SELECT l.name FROM contact_lists l
        INNER JOIN contact_list_members m ON m.list_id = l.id
        WHERE m.contact_id = :contactId
        ORDER BY l.name COLLATE NOCASE
        """
    )
    suspend fun listNamesFor(contactId: Long): List<String>
}
