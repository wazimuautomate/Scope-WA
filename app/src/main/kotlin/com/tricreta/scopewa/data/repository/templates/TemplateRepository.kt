package com.tricreta.scopewa.data.repository.templates

import android.content.Context
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.TemplateDao
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * The only thing above the data layer that knows templates live in Room.
 * UI and (later) the job runner talk to this, never to [TemplateDao].
 */
class TemplateRepository(
    private val dao: TemplateDao,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun observeAll(): Flow<List<TemplateEntity>> = dao.observeAll()

    suspend fun find(id: Long): TemplateEntity? =
        if (id == TemplateEntity.NEW_TEMPLATE_ID) null else dao.findById(id)

    /**
     * Inserts when [id] is [TemplateEntity.NEW_TEMPLATE_ID], updates otherwise.
     * Returns the row id either way, so a freshly created template can be
     * re-opened without a round trip.
     */
    suspend fun save(
        id: Long,
        name: String,
        body: String,
        knownVariables: List<String>
    ): Long {
        val timestamp = now()
        val encoded = TemplateEntity.encodeVariables(knownVariables)

        if (id == TemplateEntity.NEW_TEMPLATE_ID) {
            return dao.insert(
                TemplateEntity(
                    name = name,
                    body = body,
                    knownVariables = encoded,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
            )
        }

        val existing = dao.findById(id) ?: return dao.insert(
            TemplateEntity(
                name = name,
                body = body,
                knownVariables = encoded,
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )

        dao.update(
            existing.copy(
                name = name,
                body = body,
                knownVariables = encoded,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        fun from(context: Context): TemplateRepository =
            TemplateRepository(ScopeWaDatabase.getInstance(context).templateDao())
    }
}
