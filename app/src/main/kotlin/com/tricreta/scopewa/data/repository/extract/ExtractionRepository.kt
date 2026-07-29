package com.tricreta.scopewa.data.repository.extract

import com.tricreta.scopewa.data.db.dao.ExtractionDao
import com.tricreta.scopewa.data.db.entity.ExtractionEntity
import com.tricreta.scopewa.data.repository.contacts.ContactsRepository
import com.tricreta.scopewa.data.repository.contacts.ExportField
import com.tricreta.scopewa.data.repository.contacts.ExportFormat
import com.tricreta.scopewa.data.repository.contacts.ExportRecord
import com.tricreta.scopewa.data.repository.contacts.ContactExporter
import com.tricreta.scopewa.data.repository.contacts.parse.ParsedContact
import kotlinx.coroutines.flow.Flow

/** What saving an extraction actually did. */
data class SaveExtractionResult(
    val extractionId: Long,
    val membersRead: Int,
    val withoutNumbers: Int,
    val kept: Int,
    val imported: Int,
    val alreadyKnown: Int,
    val filterOutcome: FilterOutcome
)

/**
 * Turns a [GroupExtraction] into saved contacts and an export.
 *
 * Deliberately routes everything through Phase 2's
 * [ContactsRepository.previewImport] / [ContactsRepository.applyImport] rather
 * than writing rows itself. That gets phone normalisation, cross-import
 * deduplication and — most importantly — **suppression-list enforcement** for
 * free. Someone who replied STOP must not come back into the database because
 * they happen to be in a group that got extracted; reusing the import path is
 * what guarantees that (architecture doc section 6, layer 3).
 */
class ExtractionRepository(
    private val extractionDao: ExtractionDao,
    private val contactsRepository: ContactsRepository,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun observeHistory(): Flow<List<ExtractionEntity>> = extractionDao.observeAll()

    suspend fun history(limit: Int = 50): List<ExtractionEntity> = extractionDao.recent(limit)

    /**
     * Filters [extraction], imports the survivors as contacts tagged with the
     * group they came from, and records the run in `extractions`.
     *
     * @param listId optional contact list to add the imported people to.
     */
    suspend fun saveExtraction(
        extraction: GroupExtraction,
        filters: ExtractionFilters = ExtractionFilters(),
        listId: Long? = null
    ): SaveExtractionResult {
        val savedNumbers = if (filters.excludeSaved) {
            contactsRepository.knownNumbers()
        } else {
            emptySet()
        }

        val outcome = ExtractionFilter.apply(extraction.members, filters, savedNumbers)

        val rows = outcome.kept
            .filter { it.hasNumber }
            .map { member ->
                ParsedContact(
                    rawNumber = member.phoneE164!!,
                    name = member.displayName,
                    fields = buildMap {
                        put(FIELD_SOURCE_GROUP, extraction.groupName)
                        if (member.isAdmin) put(FIELD_GROUP_ADMIN, "yes")
                    }
                )
            }

        val plan = contactsRepository.previewImport(rows)
        val importResult = contactsRepository.applyImport(
            plan = plan,
            listId = listId,
            sourceGroup = extraction.groupName
        )

        val entity = ExtractionEntity(
            groupName = extraction.groupName,
            memberCount = extraction.members.size,
            hiddenCount = extraction.withoutNumbers.size,
            reportedMemberCount = extraction.reportedMemberCount,
            importedCount = importResult.inserted,
            extractedAt = now()
        )
        val id = extractionDao.insert(entity)

        return SaveExtractionResult(
            extractionId = id,
            membersRead = extraction.members.size,
            withoutNumbers = extraction.withoutNumbers.size,
            kept = outcome.kept.size,
            imported = importResult.inserted,
            alreadyKnown = plan.existingContacts.size,
            filterOutcome = outcome
        )
    }

    /**
     * Renders a merged multi-group extraction as file content.
     *
     * Reuses Phase 2's [ContactExporter], so extraction exports and contact
     * exports produce byte-identical formats — and, critically, inherit the
     * rule that export means **a file, never the phone's address book**. The
     * client was explicit that extracted numbers are never saved to the
     * phonebook (architecture doc section 10, Q8).
     *
     * Members whose number wasn't readable are marked with
     * [ExportRecord.hidden] when [includeUnreachable] is set. The exporter then
     * does the right thing per format on its own: writes the literal `hidden`
     * in CSV/JSON so counts stay truthful, and skips them in TXT and VCF where
     * a row without a number would be meaningless.
     */
    fun exportMerged(
        merged: MergedExtraction,
        format: ExportFormat,
        includeUnreachable: Boolean = false
    ): String {
        val source = if (includeUnreachable) merged.members else merged.withNumbers

        val records = source.map { member ->
            ExportRecord(
                phoneE164 = member.phoneE164.orEmpty(),
                name = member.displayName,
                hidden = !member.hasNumber,
                sourceGroup = member.sourceGroups.joinToString("; ")
            )
        }

        return ContactExporter.export(records, format, ExportField.Slim)
    }

    companion object {
        const val FIELD_SOURCE_GROUP = "source_group"
        const val FIELD_GROUP_ADMIN = "group_admin"
    }
}
