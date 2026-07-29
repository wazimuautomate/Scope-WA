package com.tricreta.scopewa.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 → 2.
 *
 * ## What "version 1" means here
 *
 * `app/schemas/com.tricreta.scopewa.data.db.ScopeWaDatabase/1.json` is the only
 * definition of version 1 this project has, and it was exported by CI from the
 * tree that existed *before* Phase 4, Phase 7 and the reply listener merged. So
 * the gap this migration closes is wider than the reply listener alone —
 * writing it for just the three reply columns would leave `extractions` and
 * `group_add_jobs` short of the columns their entities now declare, and Room
 * validates the post-migration schema against those entities and throws.
 *
 * Every statement below was written against that committed `1.json`, column by
 * column. If you change an entity, re-check it.
 *
 * ## Rules that make these statements safe
 *
 * - **Nullable columns get no default.** `NULL` is exactly what "this never
 *   happened" should read as for `replied_at`, `last_replied_at`,
 *   `reported_member_count`, `started_at` and friends.
 * - **Non-null columns must carry a `DEFAULT`**, because SQLite has to have
 *   something to put in the existing rows. `''` for the text columns is not a
 *   placeholder: `StringCodec` decodes an empty string to an empty list/map,
 *   which is the correct value for a job that predates those buckets.
 * - **No entity declares `@ColumnInfo(defaultValue = ...)`**, so Room does not
 *   compare defaults when it validates — but it does compare nullability and
 *   affinity, which is what these `NOT NULL` / `INTEGER` / `TEXT` choices are
 *   matching.
 * - The two indices are spelled exactly the way Room generates them, name
 *   included, because `TableInfo.Index` compares the name.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- contacts: when this person last answered (reply listener) --------
        db.execSQL("ALTER TABLE `contacts` ADD COLUMN `last_replied_at` INTEGER")

        // --- campaign_messages: the reply signal the cold-batch breaker reads --
        db.execSQL("ALTER TABLE `campaign_messages` ADD COLUMN `replied_at` INTEGER")
        db.execSQL(
            "ALTER TABLE `campaign_messages` ADD COLUMN `reply_count` INTEGER NOT NULL DEFAULT 0"
        )

        // --- extractions: Phase 4's honest counts -----------------------------
        db.execSQL("ALTER TABLE `extractions` ADD COLUMN `reported_member_count` INTEGER")
        db.execSQL(
            "ALTER TABLE `extractions` ADD COLUMN `imported_count` INTEGER NOT NULL DEFAULT 0"
        )

        // --- group_add_jobs: Phase 7 filled the whole row in ------------------
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `wa_package` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `stop_reason` TEXT")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `pending` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `names` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `provenance` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `added` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `needs_invite` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `failed` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `skipped` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `rejected_cold` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `added_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `failed_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `needs_invite_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `skipped_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `group_add_jobs` ADD COLUMN `skipped_cold_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `last_failure` TEXT")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `started_at` INTEGER")
        db.execSQL("ALTER TABLE `group_add_jobs` ADD COLUMN `finished_at` INTEGER")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_add_jobs_day_stamp` " +
                "ON `group_add_jobs` (`day_stamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_add_jobs_status` " +
                "ON `group_add_jobs` (`status`)"
        )
    }
}
