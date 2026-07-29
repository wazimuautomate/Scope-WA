package com.tricreta.scopewa.ui.contacts

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.SuppressionEntity
import com.tricreta.scopewa.data.repository.contacts.ContactExporter
import com.tricreta.scopewa.data.repository.contacts.ContactsRepository
import com.tricreta.scopewa.data.repository.contacts.ExportField
import com.tricreta.scopewa.data.repository.contacts.ExportFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A file the user asked for, waiting for them to choose where to save it. */
data class PendingExport(
    val fileName: String,
    val mimeType: String,
    val content: String
)

data class ContactsUiState(
    val message: String? = null,
    val pendingExport: PendingExport? = null,
    val busy: Boolean = false
)

/**
 * Backs the Contacts screens: lists (screenshot 02), the searchable contact
 * roll, and the blocked/opted-out list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(
    private val repository: ContactsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    val lists: StateFlow<List<ContactListSummary>> = repository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val contacts: StateFlow<List<ContactEntity>> = _query
        .flatMapLatest { repository.observeContacts(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val contactCount: StateFlow<Int> = repository.observeContactCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val optedOut: StateFlow<List<ContactEntity>> = repository.observeOptedOut()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val suppressed: StateFlow<List<SuppressionEntity>> = repository.observeSuppressed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun createList(name: String, purpose: String) = viewModelScope.launch {
        val id = repository.createList(name, purpose)
        show(
            if (id == null) "Couldn't create \"${name.trim()}\" — pick a name you're not already using."
            else "Created \"${name.trim()}\"."
        )
    }

    fun deleteList(summary: ContactListSummary) = viewModelScope.launch {
        repository.deleteList(summary.id)
        show("Deleted \"${summary.name}\". Its ${summary.contactCount} contacts are still in Contacts.")
    }

    fun setOptedOut(contact: ContactEntity, optedOut: Boolean) = viewModelScope.launch {
        repository.setOptedOut(contact, optedOut, reason = "Blocked by hand")
        show(
            if (optedOut) "${contact.label()} will never be messaged again."
            else "${contact.label()} can be messaged again."
        )
    }

    fun suppressNumber(rawNumber: String) = viewModelScope.launch {
        val number = repository.suppressNumber(rawNumber, reason = "Added by hand")
        show(
            if (number == null) "\"${rawNumber.trim()}\" isn't a usable phone number."
            else "$number is blocked. It will be skipped by every campaign."
        )
    }

    fun unsuppressNumber(entry: SuppressionEntity) = viewModelScope.launch {
        repository.unsuppressNumber(entry.phoneE164)
        show("${entry.phoneE164} is no longer blocked.")
    }

    fun deleteContacts(ids: List<Long>) = viewModelScope.launch {
        repository.deleteContacts(ids)
        show("Deleted ${ids.size} ${if (ids.size == 1) "contact" else "contacts"}.")
    }

    /**
     * Builds the export in memory, then hands it to the screen to route through
     * the system file picker. Nothing is written to the phone's address book —
     * architecture doc section 10 Q8.
     */
    fun requestExport(listId: Long?, label: String, format: ExportFormat) = viewModelScope.launch {
        _uiState.update { it.copy(busy = true) }
        val content = repository.exportContacts(listId, format, ExportField.All)
        _uiState.update {
            it.copy(
                busy = false,
                pendingExport = PendingExport(
                    fileName = ContactExporter.fileNameFor(label, format),
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
                ContactsViewModel(ContactsRepository.create(application))
            }
        }
    }
}

/** Name if we have one, number otherwise — never a blank line in the UI. */
internal fun ContactEntity.label(): String = displayName.ifBlank { phoneE164 }
