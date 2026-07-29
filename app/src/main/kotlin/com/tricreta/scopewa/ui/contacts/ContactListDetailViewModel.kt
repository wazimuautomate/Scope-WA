package com.tricreta.scopewa.ui.contacts

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.repository.contacts.ContactExporter
import com.tricreta.scopewa.data.repository.contacts.ContactsRepository
import com.tricreta.scopewa.data.repository.contacts.ExportField
import com.tricreta.scopewa.data.repository.contacts.ExportFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One list's screen: its members, and the picker for adding more. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListDetailViewModel(
    private val repository: ContactsRepository
) : ViewModel() {

    private val listId = MutableStateFlow<Long?>(null)

    private val memberQuery = MutableStateFlow("")
    private val pickerQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    val summary: StateFlow<ContactListSummary?> = listId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeList(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val members: StateFlow<List<ContactEntity>> = combine(listId, memberQuery) { id, q -> id to q }
        .flatMapLatest { (id, q) ->
            if (id == null) flowOf(emptyList()) else repository.observeContactsInList(id, q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Everyone not already in the list — what the bulk picker offers (screenshot 03). */
    val pickable: StateFlow<List<ContactEntity>> = combine(listId, pickerQuery) { id, q -> id to q }
        .flatMapLatest { (id, q) ->
            if (id == null) flowOf(emptyList()) else repository.observeContactsNotInList(id, q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setListId(id: Long) {
        if (listId.value != id) listId.value = id
    }

    fun setMemberQuery(value: String) {
        memberQuery.value = value
    }

    fun setPickerQuery(value: String) {
        pickerQuery.value = value
    }

    fun addToList(contactIds: List<Long>) = viewModelScope.launch {
        val id = listId.value ?: return@launch
        repository.addToList(id, contactIds)
        show("Added ${contactIds.size} to the list.")
    }

    fun removeFromList(contactIds: List<Long>) = viewModelScope.launch {
        val id = listId.value ?: return@launch
        repository.removeFromList(id, contactIds)
        show("Removed ${contactIds.size} from the list. They're still in Contacts.")
    }

    fun rename(name: String, purpose: String) = viewModelScope.launch {
        val id = listId.value ?: return@launch
        val ok = repository.renameList(id, name, purpose)
        if (!ok) show("Couldn't rename — that name is already taken, or blank.")
    }

    fun requestExport(format: ExportFormat) = viewModelScope.launch {
        val id = listId.value ?: return@launch
        val name = summary.value?.name ?: "contacts"
        _uiState.update { it.copy(busy = true) }
        val content = repository.exportContacts(id, format, ExportField.All)
        _uiState.update {
            it.copy(
                busy = false,
                pendingExport = PendingExport(
                    fileName = ContactExporter.fileNameFor(name, format),
                    mimeType = format.mimeType,
                    content = content
                )
            )
        }
    }

    fun exportFinished(message: String?) {
        _uiState.update { it.copy(pendingExport = null, message = message) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun show(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                ContactListDetailViewModel(ContactsRepository.create(application))
            }
        }
    }
}
