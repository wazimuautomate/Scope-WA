package com.tricreta.scopewa.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Placeholder entities.
 *
 * `docs/BUILD-PLAN.md` ("Shared hotspots") says whichever phase lands the Room
 * database first must scaffold all eight tables from architecture doc section
 * 5.3, so that later phases only ever add *columns and DAOs to their own
 * entity* instead of editing the `@Database(entities = [...])` list and
 * fighting each other over one file. Phase 3 got there first, so these are
 * Phase 3's shells of other phases' tables.
 *
 * Each one is deliberately just a primary key. The owning phase replaces the
 * body with the real columns — that is an additive change to its own entity,
 * not a change to `ScopeWaDatabase`. Nothing reads these until then.
 *
 * The database is still pre-release and built with
 * `fallbackToDestructiveMigration()`, so filling a shell in does not need a
 * migration yet — see `ScopeWaDatabase`.
 */

/** Shell — Phase 2 owns the real columns (number, name, source group, tags, saved?, opted out?, ...). */
@Entity(tableName = "contacts")
data class ContactEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 2 owns the real columns (list name, created at, ...). */
@Entity(tableName = "contact_lists")
data class ContactListEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 5 owns the real columns (list + template + pacing profile + schedule + status). */
@Entity(tableName = "campaigns")
data class CampaignEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 5 owns the real columns (one row per recipient: exact text sent, status, time, error). */
@Entity(tableName = "campaign_messages")
data class CampaignMessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 7 owns the real columns (target group, source list, restart-surviving daily counter). */
@Entity(tableName = "group_add_jobs")
data class GroupAddJobEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 4 owns the real columns (group → members pulled, when). */
@Entity(tableName = "extractions")
data class ExtractionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** Shell — Phase 1/5 own the real columns (pacing profiles, active hours, caps, which WhatsApp app). */
@Entity(tableName = "settings")
data class SettingsEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0)
