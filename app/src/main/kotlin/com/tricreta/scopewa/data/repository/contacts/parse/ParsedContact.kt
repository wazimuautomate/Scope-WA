package com.tricreta.scopewa.data.repository.contacts.parse

/**
 * One contact as it came out of a file, before any phone normalisation.
 *
 * [fields] carries every other column/property from the source row so the
 * CSV-variable half of architecture doc section 6 layer 1 (`{town}`,
 * `{last_bundle}`) still has data to work with in Phase 5.
 */
data class ParsedContact(
    val rawNumber: String,
    val name: String = "",
    val fields: Map<String, String> = emptyMap()
)
