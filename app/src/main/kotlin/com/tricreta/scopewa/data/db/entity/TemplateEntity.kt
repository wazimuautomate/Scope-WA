package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved message template — the `templates` table in architecture doc section 5.3.
 * Owned by Phase 3.
 *
 * [knownVariables] is the list of CSV column names the author expects this
 * template to be rendered against. It is not decoration: `TemplateEngine`
 * decides whether `{name|there}` is "variable with a fallback" or spintax by
 * looking the first segment up in this set, so it has to travel with the
 * template body all the way to the sender in Phase 5.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = NEW_TEMPLATE_ID,

    val name: String,

    val body: String,

    /** Comma-separated CSV column names — see [encodeVariables]/[decodeVariables]. */
    @ColumnInfo(name = "known_variables")
    val knownVariables: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    companion object {
        /** Room treats 0 on an autoGenerate key as "assign me one". */
        const val NEW_TEMPLATE_ID = 0L

        fun encodeVariables(names: List<String>): String =
            names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

        fun decodeVariables(encoded: String): List<String> =
            encoded.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
