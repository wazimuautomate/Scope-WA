package com.tricreta.scopewa.ui.running

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.dao.CampaignProgress
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.db.entity.MessageStatus
import com.tricreta.scopewa.data.repository.campaign.CampaignRepository
import com.tricreta.scopewa.jobrunner.CampaignJobService
import com.tricreta.scopewa.jobrunner.CampaignRunState
import com.tricreta.scopewa.jobrunner.RunSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wraps the campaign row so the screen can tell "Room hasn't answered yet" from
 * "there is no campaign with that id". As a bare null the two are identical,
 * and flashing the deleted-campaign empty state for a frame on every open is
 * the kind of thing the client notices and reports as a bug.
 */
data class CampaignLoad(
    val loading: Boolean = true,
    val campaign: CampaignEntity? = null
)

/**
 * One recipient as the Running screen shows them. Built in the view model so
 * the ordering rule lives in one testable place rather than inside a
 * `LazyColumn`.
 */
data class RecipientRow(
    val id: Long,
    val displayName: String,
    val phoneE164: String,
    val status: MessageStatus,
    val error: String?,
    /** 1-based place in what is still queued; 0 for anyone already finished. */
    val queuePosition: Int
) {
    /** Never a blank line in the UI — the number stands in for a missing name. */
    val label: String get() = displayName.ifBlank { phoneE164 }
}

data class CampaignRunningUiState(
    val message: String? = null
)

/**
 * Backs the Running screen from architecture doc section 7 — "live progress,
 * sent/failed/skipped, current person, next-in countdown, Pause / Resume /
 * Stop".
 *
 * Two sources, deliberately kept apart. Room ([CampaignRepository]) is the
 * durable truth: what was sent, what failed, where the queue stopped, and it
 * survives a reboot. [CampaignRunState] is the volatile truth: what the send
 * loop is doing *this second*, including the countdown. Merging them into one
 * table would mean writing to disk once a second just to animate a number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CampaignRunningViewModel(
    /**
     * The application context, never an Activity's. The run loop lives in a
     * foreground service, so Pause/Resume/Stop have to reach [CampaignJobService]
     * — and a control that outlives the screen must not hold the screen alive.
     */
    private val appContext: Context,
    private val repository: CampaignRepository
) : ViewModel() {

    private val campaignId = MutableStateFlow<Long?>(null)

    private val _uiState = MutableStateFlow(CampaignRunningUiState())
    val uiState: StateFlow<CampaignRunningUiState> = _uiState.asStateFlow()

    val campaign: StateFlow<CampaignLoad> = campaignId
        .flatMapLatest { id ->
            if (id == null) flowOf(CampaignLoad())
            else repository.observeCampaign(id).map { CampaignLoad(loading = false, campaign = it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CampaignLoad())

    val progress: StateFlow<CampaignProgress> = campaignId
        .flatMapLatest { id -> if (id == null) flowOf(EMPTY_PROGRESS) else repository.observeProgress(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY_PROGRESS)

    val recipients: StateFlow<List<RecipientRow>> = campaignId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
        }
        .map { toRows(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * The live snapshot, but only when it belongs to *this* campaign. The
     * service publishes one global flow, so without the id check a screen left
     * open on an old campaign would show another campaign's countdown.
     */
    val run: StateFlow<RunSnapshot?> = combine(campaignId, CampaignRunState.snapshot) { id, snapshot ->
        snapshot?.takeIf { id != null && it.campaignId == id }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setCampaignId(id: Long) {
        if (campaignId.value != id) campaignId.value = id
    }

    /**
     * The service writes the Paused status itself as it unwinds, so there is no
     * repository call here. Doing both would race: the loop can still be
     * mid-send when the button is pressed, and whichever write lands last wins.
     */
    fun pause() {
        if (campaignId.value == null) return
        CampaignJobService.pause(appContext)
        show("Paused. Nothing goes out until you resume — the queue is kept exactly where it is.")
    }

    /** Clears the pause reason first, so the service doesn't restart into it. */
    fun resume() = viewModelScope.launch {
        val id = campaignId.value ?: return@launch
        repository.resume(id)
        CampaignJobService.start(appContext, id)
    }

    /**
     * Stop the loop before writing the status, not after: the service must not
     * get one more send in against a campaign the user has already ended.
     * Unlike a circuit-breaker pause this is final — see [CampaignRepository.stop].
     */
    fun stop() = viewModelScope.launch {
        val id = campaignId.value ?: return@launch
        CampaignJobService.stop(appContext)
        repository.stop(id)
        show("Stopped. Anyone still queued will not be messaged.")
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun show(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    /**
     * Finished recipients first, most recent at the top, because during a run
     * the only thing worth watching is what just happened. What is still queued
     * follows in send order, numbered, so "am I near the front?" has an answer.
     */
    private fun toRows(messages: List<CampaignMessageEntity>): List<RecipientRow> {
        val pending = mutableListOf<CampaignMessageEntity>()
        val finished = mutableListOf<CampaignMessageEntity>()
        messages.forEach { message ->
            if (MessageStatus.fromName(message.status) == MessageStatus.Pending) pending += message
            else finished += message
        }

        // Skipped rows never got a sentAt, so orderIndex breaks the tie and
        // keeps them in a stable place instead of jumping around on recompose.
        val finishedRows = finished
            .sortedWith(
                compareByDescending<CampaignMessageEntity> { it.sentAt ?: Long.MIN_VALUE }
                    .thenByDescending { it.orderIndex }
            )
            .map { it.toRow(queuePosition = 0) }

        val pendingRows = pending
            .sortedBy { it.orderIndex }
            .mapIndexed { index, message -> message.toRow(queuePosition = index + 1) }

        return finishedRows + pendingRows
    }

    private fun CampaignMessageEntity.toRow(queuePosition: Int) = RecipientRow(
        id = id,
        displayName = displayName,
        phoneE164 = phoneE164,
        status = MessageStatus.fromName(status),
        error = error?.takeIf { it.isNotBlank() },
        queuePosition = queuePosition
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        private val EMPTY_PROGRESS = CampaignProgress(
            total = 0,
            sent = 0,
            failed = 0,
            skipped = 0,
            pending = 0
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                CampaignRunningViewModel(application, CampaignRepository.create(application))
            }
        }
    }
}
