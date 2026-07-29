package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `contact_lists` — architecture doc section 5.3: named lists ("Fifth 824",
 * "Waitlist"), the screen in reference screenshot 02.
 *
 * [purpose] is the "Use for…" field from screenshot 04, kept as free text
 * rather than an enum: it's a note to the user, not something the app branches on.
 */
@Entity(
    tableName = "contact_lists",
    indices = [Index(value = ["name"], unique = true)]
)
data class ContactListEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val purpose: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
