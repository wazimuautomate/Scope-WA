package com.tricreta.scopewa.ui.templates

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.brain.template.TemplateAnalyzer
import com.tricreta.scopewa.brain.template.TemplateVariables
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.data.repository.templates.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TemplateEditorViewModel(
    private val repository: TemplateRepository,
    templateId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(TemplateEditorUiState(templateId = templateId))
    val state: StateFlow<TemplateEditorUiState> = _state.asStateFlow()

    /** Only the parts of the state that change what the preview and meter say. */
    private data class InsightInputs(
        val body: String,
        val knownVariables: List<String>,
        val campaignSize: Int,
        val seed: Long
    )

    init {
        viewModelScope.launch { load(templateId) }
        viewModelScope.launch { recomputeInsightsWhenInputsChange() }
    }

    private suspend fun load(templateId: Long) {
        val existing = repository.find(templateId)
        _state.update { current ->
            if (existing == null) {
                current.copy(loading = false)
            } else {
                val stored = TemplateEntity.decodeVariables(existing.knownVariables)
                current.copy(
                    loading = false,
                    name = existing.name,
                    body = TextFieldValue(existing.body, TextRange(existing.body.length)),
                    knownVariables = stored.ifEmpty { TemplateVariables.defaultKnownNames }
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun recomputeInsightsWhenInputsChange() {
        _state
            .map { InsightInputs(it.body.text, it.knownVariables, it.campaignSize, it.previewSeed) }
            .distinctUntilChanged()
            // mapLatest: a fast typist cancels the previous render pass instead
            // of queueing 1,000-message estimates behind every keystroke.
            .mapLatest { inputs -> withContext(Dispatchers.Default) { computeInsights(inputs) } }
            .collect { insights -> _state.update { it.copy(insights = insights) } }
    }

    private fun computeInsights(inputs: InsightInputs): TemplateInsights {
        val knownNames = inputs.knownVariables
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val sampleValues = TemplateVariables.sampleValuesFor(knownNames)

        return TemplateInsights(
            analysis = TemplateAnalyzer.analyze(inputs.body, knownNames),
            previews = TemplateAnalyzer.previews(
                template = inputs.body,
                values = sampleValues,
                knownVariableNames = knownNames,
                seed = inputs.seed
            ),
            uniqueness = TemplateAnalyzer.estimateUniqueness(
                template = inputs.body,
                values = sampleValues,
                knownVariableNames = knownNames,
                campaignSize = inputs.campaignSize,
                seed = inputs.seed
            )
        )
    }

    fun onNameChanged(name: String) {
        _state.update { it.copy(name = name, nameError = null) }
    }

    fun onBodyChanged(body: TextFieldValue) {
        _state.update { it.copy(body = body, bodyError = null) }
    }

    /** Drops a variable chip or spintax block in at the caret, replacing any selection. */
    fun insertAtCursor(snippet: String) {
        _state.update { current ->
            val text = current.body.text
            val selection = current.body.selection
            val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
            val end = maxOf(selection.start, selection.end).coerceIn(start, text.length)
            val updated = text.replaceRange(start, end, snippet)
            current.copy(
                body = TextFieldValue(updated, TextRange(start + snippet.length)),
                bodyError = null
            )
        }
    }

    fun addKnownVariable(name: String) {
        val cleaned = name.trim().trim('{', '}').trim()
        if (cleaned.isEmpty()) return
        _state.update { current ->
            if (current.knownVariables.any { it.equals(cleaned, ignoreCase = true) }) current
            else current.copy(knownVariables = current.knownVariables + cleaned)
        }
    }

    fun removeKnownVariable(name: String) {
        _state.update { current ->
            current.copy(knownVariables = current.knownVariables.filterNot { it.equals(name, ignoreCase = true) })
        }
    }

    fun onCampaignSizeChanged(size: Int) {
        _state.update { it.copy(campaignSize = size) }
    }

    /** Re-rolls the preview draws without touching the template. */
    fun shufflePreviews() {
        _state.update { it.copy(previewSeed = it.previewSeed + 1) }
    }

    fun save() {
        val current = _state.value
        val name = current.name.trim()
        val body = current.body.text
        val nameError = if (name.isEmpty()) "Give it a name so you can find it later." else null
        val bodyError = if (body.isBlank()) "A template needs a message." else null

        if (nameError != null || bodyError != null) {
            _state.update { it.copy(nameError = nameError, bodyError = bodyError) }
            return
        }

        viewModelScope.launch {
            val id = repository.save(current.templateId, name, body, current.knownVariables)
            _state.update { it.copy(templateId = id, savedId = id, nameError = null, bodyError = null) }
        }
    }

    fun delete() {
        val id = _state.value.templateId
        if (id == TemplateEntity.NEW_TEMPLATE_ID) {
            _state.update { it.copy(deleted = true) }
            return
        }
        viewModelScope.launch {
            repository.delete(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    companion object {
        fun factory(context: Context, templateId: Long) = viewModelFactory {
            initializer {
                TemplateEditorViewModel(TemplateRepository.from(context.applicationContext), templateId)
            }
        }
    }
}
