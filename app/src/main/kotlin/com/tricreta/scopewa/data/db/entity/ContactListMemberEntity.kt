package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * `contact_list_members` — the many-to-many join between `contacts` and
 * `contact_lists`.
 *
 * Not named in architecture doc section 5.3's eight-table summary, because that
 * table is a plain-English list of what's stored rather than a schema. One
 * contact genuinely belongs to several lists ("Fifth 824" *and* "Waitlist"),
 * and duplicating contact rows per list would break the `phone_e164` identity
 * that dedupe and opt-out both depend on.
 *
 * `CASCADE` on both sides: deleting a list drops its memberships (not its
 * contacts), and deleting a contact removes it from every list.
 */
@Entity(
    tableName = "contact_list_members",
    primaryKeys = ["list_id", "contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["contact_id"])]
)
data class ContactListMemberEntity(
    @ColumnInfo(name = "list_id")
    val listId: Long,

    @ColumnInfo(name = "contact_id")
    val contactId: Long,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = 0
)
