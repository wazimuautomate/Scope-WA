package com.tricreta.scopewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `templates` — architecture doc section 5.3: "message body with variables +
 * spintax". Owned by Phase 3; this is Phase 2's scaffold filled in.
 *
 * [knownVariables] is the list of CSV column names the author expects this
 * template to be rendered against, and it is not decoration.
 * `brain/template/TemplateEngine` decides whether `{name|there}` means "the CSV
 * value, or *there* if it's blank" or spintax ("pick one of these two words at
 * random") by looking the first segment up in this set — so it has to travel
 * with the body all the way to the sender in Phase 5, or a template will send
 * differently than it previewed.
 *
 * Stored via Phase 2's `Converters`.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = NEW_TEMPLATE_ID,

    val name: String = "",

    /** The raw template text: `{name|there}` variables plus `{a|b|c}` spintax. */
    val body: String = "",

    @ColumnInfo(name = "known_variables")
    val knownVariables: List<String> = emptyList(),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
) {
    companion object {
        /** Room reads 0 on an autoGenerate key as "assign me one". */
        const val NEW_TEMPLATE_ID = 0L
    }
}
