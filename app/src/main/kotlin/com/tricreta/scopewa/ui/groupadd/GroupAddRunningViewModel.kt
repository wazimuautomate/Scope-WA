package com.tricreta.scopewa.ui.groupadd

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import com.tricreta.scopewa.data.repository.groupadd.GroupAddRepository
import com.tricreta.scopewa.jobrunner.GroupAddJobService
import com.tricreta.scopewa.jobrunner.GroupAddRunState
import com.tricreta.scopewa.jobrunner.GroupAddSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class GroupAddJobLoad(
    val job: GroupAddJobEntity? = null,
    val loading: Boolean = true
)

data class GroupAddRunningUiState(val message: String? = null)

/**
 * Backs the live Group Add screen. Same shape as
 * [com.tricreta.scopewa.ui.running.CampaignRunningViewModel]: durable counts
 * come from Room, the second-by-second countdown from
 * [GroupAddRunState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAddRunningViewModel(
    private val application: Application,
    private val repository: GroupAddRepository
) : ViewModel() {

    private val jobId = MutableStateFlow<Long?>(null)

    private val _uiState = MutableStateFlow(GroupAddRunningUiState())
    val uiState: StateFlow<GroupAddRunningUiState> = _uiState.asStateFlow()

    val job: StateFlow<GroupAddJobLoad> = jobId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(GroupAddJobLoad(null, loading = false))
            } else {
                repository.observeJob(id).map { entity -> GroupAddJobLoad(entity, loading = false) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupAddJobLoad())

    val run: StateFlow<GroupAddSnapshot?> = GroupAddRunState.snapshot

    fun setJobId(id: Long) {
        jobId.value = id
    }

    fun pause() {
        GroupAddJobService.pause(application)
        _uiState.update { it.copy(message = "Paused. The queue is saved.") }
    }

    fun stop() {
        GroupAddJobService.stop(application)
        _uiState.update { it.copy(message = "Stopped.") }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    /** The needs-invite bucket as shareable text — see [GroupAddRepository.inviteExport]. */
    fun inviteExport(entity: GroupAddJobEntity): String = repository.inviteExport(entity)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                GroupAddRunningViewModel(
                    application = application,
                    repository = GroupAddRepository.create(application)
                )
            }
        }
    }
}
