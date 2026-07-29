package com.tricreta.scopewa.ui.groupadd

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.installedWaPackages
import com.tricreta.scopewa.brain.groupadd.EligibilitySplit
import com.tricreta.scopewa.brain.groupadd.GroupAddPacing
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.repository.groupadd.GroupAddRepository
import com.tricreta.scopewa.jobrunner.GroupAddJobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupAddSetupUiState(
    val targetGroup: String = "",
    val selectedListId: Long? = null,
    val installedPackages: List<WaPackage> = emptyList(),
    val selectedPackage: WaPackage? = null,
    val split: EligibilitySplit? = null,
    val screening: Boolean = false,
    val addedToday: Int = 0,
    val starting: Boolean = false,
    val message: String? = null,
    val startedJobId: Long? = null
) {
    val hasWhatsApp: Boolean get() = installedPackages.isNotEmpty()

    /** Adds still allowed today — the cap belongs to the number, not this job. */
    val remainingToday: Int get() = GroupAddPacing.remainingToday(addedToday)

    /**
     * How many of the eligible people will actually be attempted today. The
     * rest are deferred, not dropped — and the screen says so, because a run
     * that silently handled 20 of 60 would look like a bug.
     */
    val attemptedToday: Int
        get() = minOf(split?.eligibleCount ?: 0, remainingToday)

    val deferredToTomorrow: Int
        get() = ((split?.eligibleCount ?: 0) - remainingToday).coerceAtLeast(0)

    val canStart: Boolean
        get() = !starting &&
            !screening &&
            targetGroup.isNotBlank() &&
            selectedPackage != null &&
            split?.hasNobody == false &&
            remainingToday > 0
}

/**
 * Backs the Group Add screen from architecture doc section 7 — "pick group →
 * pick list → strict pacing → run → result buckets".
 *
 * The screening step is the point of this screen. Architecture doc section 6
 * layer 5 says the app must **only offer to add people who have messaged the
 * user before or came from a group they already joined**, so the list the user
 * picks is not the list that gets added: it is screened first, and the number
 * of people dropped as cold is put in front of them rather than buried.
 */
class GroupAddSetupViewModel(
    private val application: Application,
    private val repository: GroupAddRepository,
    listDao: ContactListDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupAddSetupUiState())
    val uiState: StateFlow<GroupAddSetupUiState> = _uiState.asStateFlow()

    val lists: StateFlow<List<ContactListSummary>> = listDao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private var screenJob: Job? = null

    init {
        refreshInstalledPackages()
        refreshDailyCount()
    }

    fun refreshInstalledPackages() {
        val installed = installedWaPackages(application)
        _uiState.update { state ->
            state.copy(
                installedPackages = installed,
                selectedPackage = state.selectedPackage?.takeIf { it in installed }
                    ?: installed.firstOrNull()
            )
        }
    }

    /**
     * Re-read on every visit, because the cap is shared across jobs and across
     * the day — a screen showing a stale "20 left" would be promising an
     * allowance that doesn't exist.
     */
    fun refreshDailyCount() {
        viewModelScope.launch {
            val added = repository.addedToday()
            _uiState.update { it.copy(addedToday = added) }
        }
    }

    fun setTargetGroup(value: String) {
        _uiState.update { it.copy(targetGroup = value) }
    }

    fun selectList(listId: Long) {
        if (_uiState.value.selectedListId == listId) return
        _uiState.update { it.copy(selectedListId = listId) }
        rescreen()
    }

    fun selectPackage(target: WaPackage) {
        _uiState.update { it.copy(selectedPackage = target) }
    }

    fun start() {
        val state = _uiState.value
        val split = state.split ?: return
        val target = state.selectedPackage ?: return
        if (state.starting || !state.canStart) return

        _uiState.update { it.copy(starting = true) }
        viewModelScope.launch {
            val id = repository.create(
                targetGroup = state.targetGroup,
                sourceListId = state.selectedListId,
                waPackage = target.packageName,
                split = split
            )
            if (id == null) {
                _uiState.update { it.copy(starting = false, message = NOBODY_ELIGIBLE) }
                return@launch
            }
            GroupAddJobService.start(application, id)
            _uiState.update { it.copy(starting = false, startedJobId = id) }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    fun startHandled() {
        _uiState.update { it.copy(startedJobId = null) }
    }

    private fun rescreen() {
        val listId = _uiState.value.selectedListId
        screenJob?.cancel()
        if (listId == null) {
            _uiState.update { it.copy(split = null, screening = false) }
            return
        }
        _uiState.update { it.copy(screening = true) }
        screenJob = viewModelScope.launch {
            val split = repository.screen(listId)
            _uiState.update { it.copy(split = split, screening = false) }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        private const val NOBODY_ELIGIBLE =
            "Nobody on this list can be added. Everyone on it is a cold number — they've " +
                "never messaged you and they didn't come from a group you share."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                GroupAddSetupViewModel(
                    application = application,
                    repository = GroupAddRepository.create(application),
                    listDao = ScopeWaDatabase.get(application).contactListDao()
                )
            }
        }
    }
}
