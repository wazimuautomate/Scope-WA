package com.tricreta.scopewa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tricreta.scopewa.data.db.dao.TemplateDao
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.ContactListEntity
import com.tricreta.scopewa.data.db.entity.ExtractionEntity
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import com.tricreta.scopewa.data.db.entity.SettingsEntity
import com.tricreta.scopewa.data.db.entity.TemplateEntity

/**
 * The single local Room database — architecture doc section 5.3. Everything
 * stays on the phone; nothing is uploaded anywhere.
 *
 * All eight tables are declared here from day one on purpose (see
 * `docs/BUILD-PLAN.md`, "Shared hotspots"): later phases add columns and DAOs
 * to their own entity rather than editing this list, so parallel phase
 * branches don't collide on one file.
 */
@Database(
    entities = [
        ContactEntity::class,
        ContactListEntity::class,
        TemplateEntity::class,
        CampaignEntity::class,
        CampaignMessageEntity::class,
        GroupAddJobEntity::class,
        ExtractionEntity::class,
        SettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScopeWaDatabase : RoomDatabase() {

    abstract fun templateDao(): TemplateDao

    companion object {
        private const val DATABASE_NAME = "scope_wa.db"

        @Volatile
        private var instance: ScopeWaDatabase? = null

        fun getInstance(context: Context): ScopeWaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): ScopeWaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScopeWaDatabase::class.java,
                DATABASE_NAME
            )
                // Pre-release only. Phases 1–7 are still filling in the shell
                // entities above, so schema churn is expected and there is no
                // real user data to protect yet. This must be swapped for real
                // migrations before the first APK goes to the client.
                .fallbackToDestructiveMigration()
                .build()
    }
}
