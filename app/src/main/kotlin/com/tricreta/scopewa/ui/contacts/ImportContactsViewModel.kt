package com.tricreta.scopewa.ui.contacts

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.repository.contacts.ContactsRepository
import com.tricreta.scopewa.data.repository.contacts.ImportPlan
import com.tricreta.scopewa.data.repository.contacts.ImportResult
import com.tricreta.scopewa.data.repository.contacts.parse.ContactFileParser
import com.tricreta.scopewa.data.repository.contacts.parse.CsvTable
import com.tricreta.scopewa.data.repository.contacts.parse.ParsedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the import screen is in its three steps. */
enum class ImportStep { PickFile, MapColumns, Confirm, Done }

data class ImportUiState(
    val step: ImportStep = ImportStep.PickFile,
    val fileName: String = "",
    val table: CsvTable? = null,
    val phoneColumn: String? = null,
    val nameColumn: String? = null,
    val plan: ImportPlan? = null,
    val result: ImportResult? = null,
    val targetListId: Long? = null,
    val newListName: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

/**
 * Drives the import flow: pick a file → (CSV only) say which column is the
 * phone number → see exactly what will happen → confirm.
 *
 * The preview step is not decoration. Importing 20,000 rows and *then*
 * explaining that 3,000 were duplicates and 40 were opted out is far worse than
 * showing the same numbers first.
 */
class ImportContactsViewModel(
    private val repository: ContactsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    val lists: StateFlow<List<ContactListSummary>> = repository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun preselectList(listId: Long?) {
        if (listId != null && _state.value.targetListId == null) {
            _state.update { it.copy(targetListId = listId) }
        }
    }

    fun fileChosen(fileName: String, text: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null, fileName = fileName) }

        when (val parsed = ContactFileParser.parse(fileName, text)) {
            is ParsedFile.Unsupported ->
                _state.update { it.copy(busy = false, error = parsed.reason, step = ImportStep.PickFile) }

            is ParsedFile.Csv -> _state.update {
                it.copy(
                    busy = false,
                    step = ImportStep.MapColumns,
                    table = parsed.table,
                    phoneColumn = ContactFileParser.guessPhoneColumn(parsed.table),
                    nameColumn = ContactFileParser.guessNameColumn(parsed.table)
                )
            }

            is ParsedFile.Ready -> {
                val plan = repository.previewImport(parsed.contacts)
                _state.update {
                    it.copy(busy = false, step = ImportStep.Confirm, plan = plan, table = null)
                }
            }
        }
    }

    /** The file couldn't be read at all — a different problem from an unparseable one. */
    fun fileUnreadable(fileName: String) {
        _state.update {
            it.copy(
                busy = false,
                step = ImportStep.PickFile,
                error = "Couldn't read $fileName. Try copying it somewhere local first."
            )
        }
    }

    fun setPhoneColumn(column: String) {
        _state.update { it.copy(phoneColumn = column) }
    }

    /** Null clears the choice — a CSV of bare numbers has no name column. */
    fun setNameColumn(column: String?) {
        _state.update { it.copy(nameColumn = column) }
    }

    fun setTargetList(listId: Long?) {
        _state.update { it.copy(targetListId = listId, newListName = "") }
    }

    fun setNewListName(name: String) {
        _state.update { it.copy(newListName = name, targetListId = null) }
    }

    fun buildPlan() = viewModelScope.launch {
        val current = _state.value
        val table = current.table ?: return@launch
        val phoneColumn = current.phoneColumn ?: return@launch

        _state.update { it.copy(busy = true) }
        val rows = ContactFileParser.contactsFromCsv(table, phoneColumn, current.nameColumn)
        val plan = repository.previewImport(rows)
        _state.update { it.copy(busy = false, step = ImportStep.Confirm, plan = plan) }
    }

    fun confirm() = viewModelScope.launch {
        val current = _state.value
        val plan = current.plan ?: return@launch

        _state.update { it.copy(busy = true, error = null) }

        val listId = when {
            current.newListName.isNotBlank() -> repository.createList(current.newListName, "")
            else -> current.targetListId
        }

        if (current.newListName.isNotBlank() && listId == null) {
            _state.update {
                it.copy(busy = false, error = "That list name is already taken. Pick another.")
            }
            return@launch
        }

        val result = repository.applyImport(plan, listId)
        _state.update { it.copy(busy = false, step = ImportStep.Done, result = result) }
    }

    fun backToColumns() {
        _state.update { it.copy(step = if (it.table != null) ImportStep.MapColumns else ImportStep.PickFile) }
    }

    fun startOver() {
        _state.value = ImportUiState(targetListId = _state.value.targetListId)
    }

    fun errorShown() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                ImportContactsViewModel(ContactsRepository.create(application))
            }
        }
    }
}
