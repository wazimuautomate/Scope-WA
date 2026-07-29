package com.tricreta.scopewa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tricreta.scopewa.data.db.dao.ContactDao
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.SuppressionDao
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.ContactListEntity
import com.tricreta.scopewa.data.db.entity.ContactListMemberEntity
import com.tricreta.scopewa.data.db.entity.ExtractionEntity
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import com.tricreta.scopewa.data.db.entity.SettingEntity
import com.tricreta.scopewa.data.db.entity.SuppressionEntity
import com.tricreta.scopewa.data.db.entity.TemplateEntity

/**
 * The single Room database. Everything stays on the phone — architecture doc
 * section 5.3.
 *
 * ## Adding to this file
 *
 * All eight tables from architecture doc section 5.3 are registered here
 * already, scaffolded by Phase 2 exactly as `docs/BUILD-PLAN.md` asks, plus two
 * that the design needs but the prose summary doesn't name
 * (`contact_list_members`, `suppression_list`).
 *
 * **Later phases should add fields to their own entity and a DAO accessor
 * below — not new `entities = [...]` entries.** That keeps this file a
 * one-line diff per phase instead of a merge conflict.
 *
 * ## Migrations
 *
 * `exportSchema = false` and destructive fallback are deliberate *while
 * unreleased*. Nothing has shipped, so there is no user data to migrate and a
 * schema JSON would only record guesses that later phases will change. Before
 * the first signed release: turn schema export on, add a `room.schemaLocation`
 * KSP arg, commit the schema, and drop the destructive fallback. Tracked in
 * `MEMORY.md`.
 */
@Database(
    entities = [
        // Phase 2 — owned and used here
        ContactEntity::class,
        ContactListEntity::class,
        ContactListMemberEntity::class,
        SuppressionEntity::class,
        // Scaffolded for later phases — see each entity's KDoc for its owner
        TemplateEntity::class,        // Phase 3
        ExtractionEntity::class,      // Phase 4
        CampaignEntity::class,        // Phase 5
        CampaignMessageEntity::class, // Phase 5
        GroupAddJobEntity::class,     // Phase 7
        SettingEntity::class          // Phase 1 (Settings screen)
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ScopeWaDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun contactListDao(): ContactListDao
    abstract fun suppressionDao(): SuppressionDao

    companion object {
        private const val NAME = "scope_wa.db"

        @Volatile
        private var instance: ScopeWaDatabase? = null

        fun get(context: Context): ScopeWaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): ScopeWaDatabase =
            Room.databaseBuilder(context, ScopeWaDatabase::class.java, NAME)
                // Pre-release only — see the migration note in this class's KDoc.
                .fallbackToDestructiveMigration()
                .build()
    }
}
