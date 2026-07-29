package com.tricreta.scopewa.ui.activitylog

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.CampaignDao
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.TemplateDao
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.repository.report.ActivityLogExporter
import com.tricreta.scopewa.data.repository.report.CampaignReport
import com.tricreta.scopewa.data.repository.report.CampaignReportBuilder
import com.tricreta.scopewa.data.repository.report.LoggedMessage
import com.tricreta.scopewa.data.repository.report.ReportCampaign
import com.tricreta.scopewa.ui.contacts.PendingExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wraps the report so the screen can tell "Room hasn't answered yet" from
 * "there is no campaign with that id" — the same distinction
 * `CampaignRunningViewModel.CampaignLoad` draws, and for the same reason.
 */
data class ReportLoad(
    val loading: Boolean = true,
    val report: CampaignReport? = null
)

data class CampaignReportUiState(
    val message: String? = null,
    val pendingExport: PendingExport? = null,
    val busy: Boolean = false
)

/**
 * Backs the per-campaign report from architecture doc section 7.
 *
 * All the arithmetic lives in [CampaignReportBuilder], which has no Android
 * imports and is unit tested; this class only fetches rows and names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CampaignReportViewModel(
    private val campaignId: Long,
    private val campaignDao: CampaignDao,
    private val contactListDao: ContactListDao,
    private val templateDao: TemplateDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(CampaignReportUiState())
    val uiState: StateFlow<CampaignReportUiState> = _uiState.asStateFlow()

    val load: StateFlow<ReportLoad> =
        combine(
            campaignDao.observeById(campaignId),
            campaignDao.observeMessages(campaignId)
        ) { campaign, messages -> campaign to messages }
            .mapLatest { (campaign, messages) ->
                if (campaign == null) {
                    ReportLoad(loading = false, report = null)
                } else {
                    ReportLoad(loading = false, report = buildReport(campaign, messages))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReportLoad())

    fun requestExport(asText: Boolean) = viewModelScope.launch {
        val report = load.value.report ?: return@launch
        _uiState.update { it.copy(busy = true) }
        val csvName = ActivityLogExporter.reportFileName(report.campaign.name)
        _uiState.update {
            it.copy(
                busy = false,
                pendingExport = PendingExport(
                    fileName = if (asText) csvName.removeSuffix(".csv") + ".txt" else csvName,
                    mimeType = if (asText) TEXT_MIME else CSV_MIME,
                    content = if (asText) {
                        ActivityLogExporter.reportText(report)
                    } else {
                        ActivityLogExporter.reportCsv(report)
                    }
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

    /**
     * The list and template are looked up by id every time rather than stored
     * on the campaign. Either can be renamed after a campaign ran, and the
     * report should read as the client's world reads today; a deleted one
     * simply drops out of the header rather than showing a bare row id.
     */
    private suspend fun buildReport(
        campaign: CampaignEntity,
        messages: List<CampaignMessageEntity>
    ): CampaignReport {
        val listName = campaign.listId?.let { contactListDao.byId(it)?.name }
        val templateName = campaign.templateId?.let { templateDao.findById(it)?.name }

        val reportCampaign = ReportCampaign(
            id = campaign.id,
            name = campaign.name,
            templateName = templateName,
            listName = listName,
            status = campaign.status,
            pacingProfile = campaign.pacingProfile,
            startedAt = campaign.startedAt,
            finishedAt = campaign.finishedAt,
            pauseReason = campaign.pauseReason
        )

        return CampaignReportBuilder.build(
            campaign = reportCampaign,
            messages = messages.map { it.toLoggedMessage(campaign.name) }
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val CSV_MIME = "text/csv"
        private const val TEXT_MIME = "text/plain"

        fun factory(campaignId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                val database = ScopeWaDatabase.get(application)
                CampaignReportViewModel(
                    campaignId = campaignId,
                    campaignDao = database.campaignDao(),
                    contactListDao = database.contactListDao(),
                    templateDao = database.templateDao()
                )
            }
        }
    }
}

/** The Room entity, flattened into the pure model the report builder understands. */
internal fun CampaignMessageEntity.toLoggedMessage(campaignName: String): LoggedMessage =
    LoggedMessage(
        campaignId = campaignId,
        campaignName = campaignName,
        phoneE164 = phoneE164,
        displayName = displayName,
        renderedText = renderedText,
        status = status,
        sentAt = sentAt,
        error = error
    )
