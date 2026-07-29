package com.tricreta.scopewa.ui.templates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.brain.template.TemplateAnalyzer
import com.tricreta.scopewa.brain.template.TemplateVariables
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.data.repository.templates.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class TemplateListViewModel(private val repository: TemplateRepository) : ViewModel() {

    val templates: StateFlow<List<TemplateListItem>> = repository.observeAll()
        .map { rows -> rows.map { it.toListItem() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private fun TemplateEntity.toListItem(): TemplateListItem {
        val known = knownVariables
            .ifEmpty { TemplateVariables.defaultKnownNames }
            .map { it.lowercase() }
            .toSet()
        val analysis = TemplateAnalyzer.analyze(body, known)

        return TemplateListItem(
            id = id,
            name = name,
            bodyPreview = body.replace('\n', ' ').trim().take(BODY_PREVIEW_CHARS),
            spintaxBlocks = analysis.spintax.size,
            variableCount = analysis.variables.size,
            combinations = analysis.combinations,
            combinationsCapped = analysis.isCombinationCountCapped
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val BODY_PREVIEW_CHARS = 120

        fun factory(context: Context) = viewModelFactory {
            initializer { TemplateListViewModel(TemplateRepository.from(context.applicationContext)) }
        }
    }
}
