package com.tricreta.scopewa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tricreta.scopewa.data.db.dao.CampaignDao
import com.tricreta.scopewa.data.db.dao.ContactDao
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.ExtractionDao
import com.tricreta.scopewa.data.db.dao.GroupAddJobDao
import com.tricreta.scopewa.data.db.dao.SuppressionDao
import com.tricreta.scopewa.data.db.dao.TemplateDao
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
 * Schema export is on (`app/schemas/`, committed) and there is no destructive
 * fallback — both changed for the 1.0.0 release. Version 1 needs no `Migration`
 * objects because it is the first shipped schema.
 *
 * **From here on, every schema change needs a real `Migration` plus a `version`
 * bump.** There is app data on real phones now, and without destructive
 * fallback a missing migration is not a silent wipe — it is an
 * `IllegalStateException` the first time the user opens the app after updating.
 * Commit the new `app/schemas/<n>.json` alongside the migration.
 */
@Database(
    entities = [
        // Phase 2 — owned and used here
        ContactEntity::class,
        ContactListEntity::class,
        ContactListMemberEntity::class,
        SuppressionEntity::class,
        // Scaffolded for later phases — see each entity's KDoc for its owner
        TemplateEntity::class,        // Phase 3 — real columns landed with Phase 3
        ExtractionEntity::class,      // Phase 4
        CampaignEntity::class,        // Phase 5
        CampaignMessageEntity::class, // Phase 5
        GroupAddJobEntity::class,     // Phase 7
        SettingEntity::class          // Phase 1 (Settings screen)
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ScopeWaDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun contactListDao(): ContactListDao
    abstract fun suppressionDao(): SuppressionDao
    abstract fun templateDao(): TemplateDao
    abstract fun extractionDao(): ExtractionDao
    abstract fun campaignDao(): CampaignDao
    abstract fun groupAddJobDao(): GroupAddJobDao

    companion object {
        private const val NAME = "scope_wa.db"

        @Volatile
        private var instance: ScopeWaDatabase? = null

        fun get(context: Context): ScopeWaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Alias for [get]. Phase 2 and Phase 3 were written in parallel and
         * picked different names for this; keeping both costs one line and
         * saves rewriting either phase's call sites.
         */
        fun getInstance(context: Context): ScopeWaDatabase = get(context)

        private fun build(context: Context): ScopeWaDatabase =
            Room.databaseBuilder(context, ScopeWaDatabase::class.java, NAME)
                // No .fallbackToDestructiveMigration() and no .addMigrations(...):
                // v1 is the first shipped schema. Any later version must add a
                // real Migration here — see this class's KDoc.
                .build()
    }
}
