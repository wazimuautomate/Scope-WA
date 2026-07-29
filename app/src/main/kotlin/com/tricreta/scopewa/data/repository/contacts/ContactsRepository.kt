package com.tricreta.scopewa.data.repository.contacts

import android.content.Context
import androidx.room.withTransaction
import com.tricreta.scopewa.brain.phone.NormalizedPhone
import com.tricreta.scopewa.brain.phone.PhoneNormalizer
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.ContactDao
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.dao.SuppressionDao
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.ContactListEntity
import com.tricreta.scopewa.data.db.entity.ContactListMemberEntity
import com.tricreta.scopewa.data.db.entity.SuppressionEntity
import com.tricreta.scopewa.data.repository.contacts.parse.ParsedContact
import kotlinx.coroutines.flow.Flow

/** What actually happened, once an [ImportPlan] was written to the database. */
data class ImportResult(
    val plan: ImportPlan,
    val inserted: Int,
    val updated: Int,
    val addedToList: Int,
    val listName: String? = null
)

/**
 * The only thing the UI and job runner talk to for contacts. Wraps Room and
 * hands the interesting decisions to the pure logic in [ContactImporter] /
 * [ContactExporter], which is where the tests live.
 */
class ContactsRepository(
    private val database: ScopeWaDatabase,
    private val contactDao: ContactDao,
    private val listDao: ContactListDao,
    private val suppressionDao: SuppressionDao,
    private val importer: ContactImporter = ContactImporter(),
    private val normalizer: PhoneNormalizer = PhoneNormalizer(),
    private val now: () -> Long = System::currentTimeMillis
) {

    // ---- reads -------------------------------------------------------------

    fun observeLists(): Flow<List<ContactListSummary>> = listDao.observeSummaries()

    fun observeList(listId: Long): Flow<ContactListSummary?> = listDao.observeSummary(listId)

    fun observeContacts(query: String): Flow<List<ContactEntity>> = contactDao.observeAll(query)

    fun observeContactsInList(listId: Long, query: String): Flow<List<ContactEntity>> =
        contactDao.observeInList(listId, query)

    fun observeContactsNotInList(listId: Long, query: String): Flow<List<ContactEntity>> =
        contactDao.observeNotInList(listId, query)

    fun observeContactCount(): Flow<Int> = contactDao.observeCount()

    fun observeOptedOut(): Flow<List<ContactEntity>> = contactDao.observeOptedOut()

    fun observeSuppressed(): Flow<List<SuppressionEntity>> = suppressionDao.observeAll()

    // ---- lists -------------------------------------------------------------

    /** @return the new list's id, or null when the name is already taken. */
    suspend fun createList(name: String, purpose: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (listDao.byName(trimmed) != null) return null
        val timestamp = now()
        return listDao.insert(
            ContactListEntity(
                name = trimmed,
                purpose = purpose.trim(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
    }

    suspend fun renameList(listId: Long, name: String, purpose: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val clash = listDao.byName(trimmed)
        if (clash != null && clash.id != listId) return false
        listDao.rename(listId, trimmed, purpose.trim(), now())
        return true
    }

    /** Drops the list and its memberships. The contacts themselves survive. */
    suspend fun deleteList(listId: Long) = listDao.deleteList(listId)

    suspend fun addToList(listId: Long, contactIds: List<Long>) {
        if (contactIds.isEmpty()) return
        val timestamp = now()
        database.withTransaction {
            contactIds.chunked(CHUNK).forEach { chunk ->
                listDao.addMembers(chunk.map { ContactListMemberEntity(listId, it, timestamp) })
            }
        }
    }

    suspend fun removeFromList(listId: Long, contactIds: List<Long>) {
        if (contactIds.isEmpty()) return
        database.withTransaction {
            contactIds.chunked(CHUNK).forEach { listDao.removeMembers(listId, it) }
        }
    }

    // ---- import ------------------------------------------------------------

    /**
     * Works out what an import would do without touching the database, so the
     * import screen can show the user the numbers first.
     */
    suspend fun previewImport(rows: List<ParsedContact>): ImportPlan {
        val numbers = rows.mapNotNull { row ->
            (normalizer.normalize(row.rawNumber) as? NormalizedPhone.Valid)?.e164
        }.distinct()

        val existing = numbers.chunked(CHUNK)
            .flatMap { contactDao.byNumbers(it) }
            .map { it.phoneE164 }
            .toSet()

        return importer.plan(
            rows = rows,
            existingNumbers = existing,
            suppressedNumbers = suppressedNumbers()
        )
    }

    /**
     * Applies a plan: inserts the new contacts, fills in blanks on the ones we
     * already had, and — if [listId] is given — adds everything importable to
     * that list. Suppressed numbers are not written and not listed.
     */
    suspend fun applyImport(plan: ImportPlan, listId: Long? = null): ImportResult {
        if (plan.isEmpty) {
            return ImportResult(plan, inserted = 0, updated = 0, addedToList = 0)
        }

        val timestamp = now()
        var inserted = 0
        var updated = 0

        database.withTransaction {
            plan.newContacts.chunked(CHUNK).forEach { chunk ->
                val ids = contactDao.insertAll(
                    chunk.map { candidate ->
                        ContactEntity(
                            phoneE164 = candidate.phoneE164,
                            rawNumber = candidate.rawNumber,
                            displayName = candidate.name,
                            customFields = candidate.fields,
                            createdAt = timestamp,
                            updatedAt = timestamp
                        )
                    }
                )
                inserted += ids.count { it != -1L }
            }

            // Merge, never overwrite: a name or field the user already has is
            // better data than whatever a re-imported CSV happens to say.
            plan.existingContacts.chunked(CHUNK).forEach { chunk ->
                val stored = contactDao.byNumbers(chunk.map { it.phoneE164 })
                    .associateBy { it.phoneE164 }
                val merged = chunk.mapNotNull { candidate ->
                    val current = stored[candidate.phoneE164] ?: return@mapNotNull null
                    val next = current.copy(
                        displayName = current.displayName.ifBlank { candidate.name },
                        customFields = candidate.fields + current.customFields,
                        updatedAt = timestamp
                    )
                    next.takeIf { it != current }
                }
                if (merged.isNotEmpty()) {
                    contactDao.updateAll(merged)
                    updated += merged.size
                }
            }
        }

        var addedToList = 0
        var listName: String? = null
        if (listId != null) {
            val ids = idsForNumbers(plan.importable.map { it.phoneE164 })
            addToList(listId, ids)
            addedToList = ids.size
            listName = listDao.byId(listId)?.name
        }

        return ImportResult(plan, inserted, updated, addedToList, listName)
    }

    private suspend fun idsForNumbers(numbers: List<String>): List<Long> =
        numbers.chunked(CHUNK).flatMap { contactDao.byNumbers(it) }.map { it.id }

    // ---- opt-out and suppression ------------------------------------------

    /**
     * Marking a contact opted out also puts the *number* on the suppression
     * list, so the block survives the contact row being deleted and re-imported.
     */
    suspend fun setOptedOut(contact: ContactEntity, optedOut: Boolean, reason: String) {
        val timestamp = now()
        database.withTransaction {
            contactDao.setOptedOut(contact.id, optedOut, reason.takeIf { optedOut }, timestamp)
            if (optedOut) {
                suppressionDao.add(SuppressionEntity(contact.phoneE164, reason, timestamp))
            } else {
                suppressionDao.remove(contact.phoneE164)
            }
        }
    }

    /** @return the normalised number that was suppressed, or null if unusable. */
    suspend fun suppressNumber(rawNumber: String, reason: String): String? {
        val normalized = normalizer.normalize(rawNumber) as? NormalizedPhone.Valid ?: return null
        val timestamp = now()
        database.withTransaction {
            suppressionDao.add(SuppressionEntity(normalized.e164, reason, timestamp))
            contactDao.optOutByNumber(normalized.e164, reason, timestamp)
        }
        return normalized.e164
    }

    suspend fun unsuppressNumber(number: String) {
        val timestamp = now()
        database.withTransaction {
            suppressionDao.remove(number)
            contactDao.clearOptOutByNumber(number, timestamp)
        }
    }

    /** Everything Phase 5 must never send to: explicit blocks plus opt-outs. */
    suspend fun suppressedNumbers(): Set<String> =
        (suppressionDao.allNumbers() + contactDao.optedOutNumbers()).toSet()

    // ---- delete ------------------------------------------------------------

    suspend fun deleteContacts(contactIds: List<Long>) {
        if (contactIds.isEmpty()) return
        database.withTransaction {
            contactIds.chunked(CHUNK).forEach { contactDao.deleteByIds(it) }
        }
    }

    // ---- export ------------------------------------------------------------

    /**
     * Builds the export payload for a whole list, or for every contact when
     * [listId] is null. The result is file content and nothing else — per the
     * client's answer in architecture doc section 10 Q8, contacts are never
     * written into the phone's address book.
     */
    suspend fun exportContacts(
        listId: Long?,
        format: ExportFormat,
        fields: List<ExportField> = ExportField.All
    ): String {
        val contacts = if (listId == null) contactDao.all() else contactDao.allInList(listId)
        val records = contacts.map { contact ->
            ExportRecord(
                name = contact.displayName,
                phoneE164 = contact.phoneE164,
                saved = contact.isSaved,
                optedOut = contact.optedOut,
                tags = contact.tags,
                sourceGroup = contact.sourceGroup,
                lists = if (listId == null) listDao.listNamesFor(contact.id) else emptyList()
            )
        }
        return ContactExporter.export(records, format, fields)
    }

    companion object {
        /**
         * SQLite allows 999 bound variables per statement by default; chunking
         * well under that keeps a 20,000-contact import (the client's stated
         * ceiling — architecture doc section 10 Q3) from blowing up.
         */
        private const val CHUNK = 400

        fun create(context: Context): ContactsRepository {
            val database = ScopeWaDatabase.get(context)
            return ContactsRepository(
                database = database,
                contactDao = database.contactDao(),
                listDao = database.contactListDao(),
                suppressionDao = database.suppressionDao()
            )
        }
    }
}
