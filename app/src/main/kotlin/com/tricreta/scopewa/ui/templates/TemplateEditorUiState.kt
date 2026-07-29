package com.tricreta.scopewa.ui.templates

import androidx.compose.ui.text.input.TextFieldValue
import com.tricreta.scopewa.brain.template.TemplateAnalysis
import com.tricreta.scopewa.brain.template.TemplateAnalyzer
import com.tricreta.scopewa.brain.template.TemplateVariables
import com.tricreta.scopewa.brain.uniqueness.UniquenessResult
import com.tricreta.scopewa.data.db.entity.TemplateEntity

/** Everything the editor derives from the template body, recomputed off the main thread. */
data class TemplateInsights(
    val analysis: TemplateAnalysis = TemplateAnalysis.EMPTY,
    val previews: List<String> = emptyList(),
    val uniqueness: UniquenessResult = UniquenessResult(total = 0, uniqueCount = 0, duplicateIndices = emptyList())
)

data class TemplateEditorUiState(
    val loading: Boolean = true,
    val templateId: Long = TemplateEntity.NEW_TEMPLATE_ID,
    val name: String = "",
    val body: TextFieldValue = TextFieldValue(""),
    /**
     * The CSV column names this template will be rendered against. Editable,
     * because `TemplateEngine` reads `{name|there}` as "value or fallback" only
     * when `name` is in this set — otherwise it is spintax and the client would
     * be randomly greeting people as "name".
     */
    val knownVariables: List<String> = TemplateVariables.defaultKnownNames,
    val campaignSize: Int = TemplateAnalyzer.DEFAULT_CAMPAIGN_SIZE,
    val previewSeed: Long = 0L,
    val insights: TemplateInsights = TemplateInsights(),
    val savedId: Long? = null,
    val deleted: Boolean = false,
    val nameError: String? = null,
    val bodyError: String? = null
) {
    val isExistingTemplate: Boolean
        get() = templateId != TemplateEntity.NEW_TEMPLATE_ID

    val canSave: Boolean
        get() = !loading && body.text.isNotBlank()

    companion object {
        /** Campaign sizes offered under the uniqueness meter. 200 matches the doc's example. */
        val CAMPAIGN_SIZES = listOf(100, 200, 500, 1_000)
    }
}

/** One row in the saved-templates list. */
data class TemplateListItem(
    val id: Long,
    val name: String,
    val bodyPreview: String,
    val spintaxBlocks: Int,
    val variableCount: Int,
    val combinations: Long,
    val combinationsCapped: Boolean
)
