package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `templates` — architecture doc section 5.3: "message body with variables +
 * spintax".
 *
 * **Scaffold only — Phase 3 owns this table.** It exists here because
 * `docs/BUILD-PLAN.md` asks whichever phase lands first to register all eight
 * entities in one go, so later phases add *fields*, not new `@Database`
 * entries. Phase 3 should extend this freely; nothing in Phase 2 reads it.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "",

    /** The raw template text: `{name|there}` variables plus `{a|b|c}` spintax. */
    val body: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
)
