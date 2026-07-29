package com.tricreta.scopewa.ui.extract

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tricreta.scopewa.accessibility.ExtractionOutcome
import com.tricreta.scopewa.accessibility.ExtractionProgress
import com.tricreta.scopewa.accessibility.GroupExtractor
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.WaServiceBridge
import com.tricreta.scopewa.accessibility.installedWaPackages
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.entity.ExtractionEntity
import com.tricreta.scopewa.data.repository.contacts.ContactExporter
import com.tricreta.scopewa.data.repository.contacts.ContactsRepository
import com.tricreta.scopewa.data.repository.contacts.ExportFormat
import com.tricreta.scopewa.data.repository.extract.ExtractionFilters
import com.tricreta.scopewa.data.repository.extract.ExtractionMerger
import com.tricreta.scopewa.data.repository.extract.ExtractionRepository
import com.tricreta.scopewa.data.repository.extract.GroupExtraction
import com.tricreta.scopewa.data.repository.extract.SaveExtractionResult
import com.tricreta.scopewa.ui.contacts.PendingExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class ExtractState {
    data object Idle : ExtractState()

    /** Counting down while the user switches to WhatsApp's group info screen. */
    data class CountingDown(val secondsLeft: Int) : ExtractState()

    data class Reading(val progress: ExtractionProgress?) : ExtractState()

    data class Finished(
        val extraction: GroupExtraction,
        val saved: SaveExtractionResult?
    ) : ExtractState()

    data class Failed(val headline: String, val detail: String, val diagnostics: String? = null) :
        ExtractState()
}

data class ExtractUiState(
    val serviceConnected: Boolean = false,
    val installedPackages: List<WaPackage> = emptyList(),
    val filters: ExtractionFilters = ExtractionFilters(),
    val state: ExtractState = ExtractState.Idle,
    val history: List<ExtractionEntity> = emptyList(),
    val pendingExport: PendingExport? = null,
    val message: String? = null
) {
    val canExtract: Boolean get() = serviceConnected && installedPackages.isNotEmpty()
}

/**
 * Drives the Extract screen — Phase 4 of `docs/BUILD-PLAN.md`.
 *
 * Like the Phase 1 diagnostics capture, extraction is **delayed**: while the
 * user is looking at Scope WA, the foreground window is Scope WA. To read a
 * group's participants the user must be on that screen, so the flow counts
 * down, then reads whatever group info is in front.
 */
class ExtractViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ExtractionRepository(
        extractionDao = ScopeWaDatabase.get(application).extractionDao(),
        contactsRepository = ContactsRepository.create(application)
    )
    private val extractor = GroupExtractor()

    private val _uiState = MutableStateFlow(ExtractUiState())
    val uiState: StateFlow<ExtractUiState> = _uiState.asStateFlow()

    val history: StateFlow<List<ExtractionEntity>> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            WaServiceBridge.isConnected.collect { connected ->
                _uiState.update { it.copy(serviceConnected = connected) }
            }
        }
        refresh()
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                installedPackages = installedWaPackages(getApplication()),
                serviceConnected = WaServiceBridge.isConnected.value
            )
        }
    }

    fun setFilters(filters: ExtractionFilters) {
        _uiState.update { it.copy(filters = filters) }
    }

    fun reset() {
        _uiState.update { it.copy(state = ExtractState.Idle) }
    }

    /**
     * Counts down, then reads the group-info screen in front and saves the
     * result.
     *
     * @param listId optional contact list to add the extracted people to.
     */
    fun startExtraction(countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS, listId: Long? = null) {
        val target = _uiState.value.installedPackages.firstOrNull() ?: return
        if (_uiState.value.state is ExtractState.Reading) return

        viewModelScope.launch {
            if (!WaServiceBridge.isConnected.value) {
                fail(
                    "Accessibility permission isn't active",
                    "Scope WA can't read WhatsApp's screen yet. Finish setup first."
                )
                return@launch
            }

            for (remaining in countdownSeconds downTo 1) {
                _uiState.update { it.copy(state = ExtractState.CountingDown(remaining)) }
                kotlinx.coroutines.delay(1_000)
            }

            _uiState.update { it.copy(state = ExtractState.Reading(null)) }

            val outcome = extractor.extractCurrentGroup(
                packageName = target.packageName,
                onProgress = { progress ->
                    _uiState.update { it.copy(state = ExtractState.Reading(progress)) }
                }
            )

            when (outcome) {
                is ExtractionOutcome.Success -> {
                    val saved = runCatching {
                        repository.saveExtraction(
                            extraction = outcome.extraction,
                            filters = _uiState.value.filters,
                            listId = listId
                        )
                    }.getOrNull()

                    _uiState.update {
                        it.copy(state = ExtractState.Finished(outcome.extraction, saved))
                    }
                }

                ExtractionOutcome.ServiceNotConnected -> fail(
                    "Accessibility permission isn't active",
                    "Scope WA lost its connection to the screen. Re-check it in Setup."
                )

                is ExtractionOutcome.NotOnGroupInfoScreen -> fail(
                    "That wasn't a WhatsApp group screen",
                    buildString {
                        append("The screen in front was ")
                        append(outcome.foregroundPackage ?: "not readable")
                        append(". Open the group in WhatsApp, tap its name to open Group info, ")
                        append("then start the countdown and switch across.")
                    }
                )

                is ExtractionOutcome.NoMembersRead -> fail(
                    "No participants could be read",
                    "Scope WA could see WhatsApp but recognised no participant rows. Either " +
                        "the group info screen wasn't open, or WhatsApp has changed its " +
                        "layout — in which case the details below are what a fix needs.",
                    outcome.screenDump
                )
            }
        }
    }

    private fun fail(headline: String, detail: String, diagnostics: String? = null) {
        _uiState.update { it.copy(state = ExtractState.Failed(headline, detail, diagnostics)) }
    }

    /**
     * Builds the just-finished extraction as a file and hands it to the screen
     * to route through the system file picker.
     *
     * Includes name-only members by default — see [ExtractionRepository.exportMerged]
     * — because the point of this export is a complete record of who's in the
     * group, not only the subset with a messageable number.
     */
    fun exportCurrent(format: ExportFormat) {
        val finished = _uiState.value.state as? ExtractState.Finished ?: return

        viewModelScope.launch {
            val merged = ExtractionMerger.merge(listOf(finished.extraction))
            val content = repository.exportMerged(merged, format)
            _uiState.update {
                it.copy(
                    pendingExport = PendingExport(
                        fileName = ContactExporter.fileNameFor(finished.extraction.groupName, format),
                        mimeType = format.mimeType,
                        content = content
                    )
                )
            }
        }
    }

    fun exportFinished(message: String?) {
        _uiState.update { it.copy(pendingExport = null, message = message) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 10
    }
}
