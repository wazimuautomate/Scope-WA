package com.tricreta.scopewa.ui.activitylog

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.ANY_CAMPAIGN
import com.tricreta.scopewa.data.db.dao.ANY_STATUS
import com.tricreta.scopewa.data.db.dao.ActivityLogEntry
import com.tricreta.scopewa.data.db.dao.CampaignDao
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.MessageStatus
import com.tricreta.scopewa.data.repository.report.ActivityLogExporter
import com.tricreta.scopewa.data.repository.report.LoggedMessage
import com.tricreta.scopewa.ui.contacts.PendingExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The four chips above the log. `All` is the sentinel, not a real status. */
enum class ActivityStatusFilter(val label: String, val queryValue: String) {
    All("All", ANY_STATUS),
    Sent("Sent", MessageStatus.Sent.name),
    Failed("Failed", MessageStatus.Failed.name),
    Skipped("Skipped", MessageStatus.Skipped.name)
}

/**
 * Everything that decides which rows are on screen. Kept as one value so the
 * query re-runs on exactly one flow change — including [limit], which is why
 * "Show more" is a filter change rather than a second pagination mechanism.
 */
data class ActivityLogFilter(
    val status: ActivityStatusFilter = ActivityStatusFilter.All,
    val campaignId: Long = ANY_CAMPAIGN,
    val limit: Int = ActivityLogViewModel.PAGE_SIZE
)

data class ActivityLogUiState(
    val message: String? = null,
    val pendingExport: PendingExport? = null,
    val busy: Boolean = false
)

/**
 * Backs the activity log from architecture doc section 7 — "everything sent,
 * exportable CSV".
 *
 * Reads the DAO directly rather than going through `CampaignRepository`. The
 * repository exists to *change* campaigns safely (pacing, caps, opt-outs); the
 * log only ever reads, and routing a read-only screen through the send path
 * would be the wrong kind of coupling for a screen this incidental.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityLogViewModel(
    private val dao: CampaignDao,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _filter = MutableStateFlow(ActivityLogFilter())
    val filter: StateFlow<ActivityLogFilter> = _filter.asStateFlow()

    private val _uiState = MutableStateFlow(ActivityLogUiState())
    val uiState: StateFlow<ActivityLogUiState> = _uiState.asStateFlow()

    val entries: StateFlow<List<ActivityLogEntry>> = _filter
        .flatMapLatest { dao.observeActivityLog(it.campaignId, it.status.queryValue, it.limit, 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val matchingCount: StateFlow<Int> = _filter
        .flatMapLatest { dao.observeActivityLogCount(it.campaignId, it.status.queryValue) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** Feeds the campaign filter, and the "open the report" list. */
    val campaigns: StateFlow<List<CampaignEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setStatusFilter(status: ActivityStatusFilter) {
        _filter.update { it.copy(status = status, limit = PAGE_SIZE) }
    }

    fun setCampaignFilter(campaignId: Long) {
        _filter.update { it.copy(campaignId = campaignId, limit = PAGE_SIZE) }
    }

    fun showMore() {
        _filter.update { it.copy(limit = it.limit + PAGE_SIZE) }
    }

    /**
     * Exports **everything the current filter matches**, not the page on
     * screen. A file that quietly stops at row 200 because that is how far the
     * user happened to scroll would be worse than no export at all.
     */
    fun requestExport() = viewModelScope.launch {
        _uiState.update { it.copy(busy = true) }
        val current = _filter.value
        val rows = dao.activityLog(current.campaignId, current.status.queryValue, EXPORT_LIMIT, 0)
        val content = ActivityLogExporter.activityLogCsv(rows.map { it.toLoggedMessage() })
        _uiState.update {
            it.copy(
                busy = false,
                pendingExport = PendingExport(
                    fileName = ActivityLogExporter.activityLogFileName(now()),
                    mimeType = CSV_MIME,
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

    companion object {
        /** Big enough that the client rarely taps "Show more", small enough to scroll. */
        const val PAGE_SIZE = 200

        /** The client's ceiling is 20k contacts; a whole history still fits in one file. */
        private const val EXPORT_LIMIT = 200_000

        private const val CSV_MIME = "text/csv"
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                ActivityLogViewModel(ScopeWaDatabase.get(application).campaignDao())
            }
        }
    }
}

/** The Room projection, flattened into the pure model the exporter understands. */
internal fun ActivityLogEntry.toLoggedMessage(): LoggedMessage = LoggedMessage(
    campaignId = campaignId,
    campaignName = campaignName.orEmpty().ifBlank { "Deleted campaign" },
    phoneE164 = phoneE164,
    displayName = displayName,
    renderedText = renderedText,
    status = status,
    sentAt = sentAt,
    error = error
)
