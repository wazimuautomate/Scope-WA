package com.tricreta.scopewa.ui.campaign

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.installedWaPackages
import com.tricreta.scopewa.brain.campaign.PacingProfileCatalog
import com.tricreta.scopewa.brain.campaign.RecipientOrdering
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.ContactListSummary
import com.tricreta.scopewa.data.db.dao.TemplateDao
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.data.repository.campaign.CampaignPreview
import com.tricreta.scopewa.data.repository.campaign.CampaignRepository
import com.tricreta.scopewa.jobrunner.CampaignJobService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * When a campaign starts sending.
 *
 * Four fixed choices, and deliberately **no recurrence option** — a repeating
 * blast to the same list is the single fastest way to get a number banned, so
 * the architecture doc drops that screen rather than building it (section 7,
 * reference screenshot 10 is marked SKIP). Scheduling later is fine; scheduling
 * *forever* is not.
 */
enum class ScheduleChoice(val label: String) {
    Now("Right now"),
    InFifteenMinutes("In 15 minutes"),
    InOneHour("In 1 hour"),
    TomorrowMorning("Tomorrow morning (9am)");

    /** Null means "no scheduled start" — the campaign runs as soon as it is created. */
    fun startAtMillis(nowMillis: Long, zone: ZoneId): Long? = when (this) {
        Now -> null
        InFifteenMinutes -> nowMillis + 15L * MILLIS_PER_MINUTE
        InOneHour -> nowMillis + 60L * MILLIS_PER_MINUTE
        // 9am tomorrow rather than "in 24 hours": it has to land inside the
        // 8am–8pm active-hours window from section 6 layer 2, and a fixed
        // offset wouldn't.
        TomorrowMorning -> Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atTime(MORNING_HOUR, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
}

data class CampaignComposerUiState(
    val selectedListId: Long? = null,
    val selectedTemplateId: Long? = null,
    val pacingProfile: String = PacingProfileCatalog.NORMAL,
    val installedPackages: List<WaPackage> = emptyList(),
    val selectedPackage: WaPackage? = null,
    val schedule: ScheduleChoice = ScheduleChoice.Now,
    val preview: CampaignPreview? = null,
    val previewing: Boolean = false,
    val starting: Boolean = false,
    val message: String? = null,
    val startedCampaignId: Long? = null
) {
    /** No WhatsApp, no campaign — this app never messages anyone by itself. */
    val hasWhatsApp: Boolean get() = installedPackages.isNotEmpty()

    /**
     * [CampaignPreview.canStart] is false when the queue is empty, which is the
     * common case for a list that has already been messaged this week. Gating
     * the button on the preview rather than on "a list is selected" is what
     * stops the user creating an empty campaign and wondering why nothing sent.
     */
    val canStart: Boolean
        get() = !starting && !previewing && selectedPackage != null && preview?.canStart == true
}

/**
 * Backs the Campaign composer from architecture doc section 7 — "pick list →
 * pick template → pacing profile → schedule → preview → Start", reference
 * screenshots 08 and 09.
 *
 * Everything expensive happens in [CampaignRepository.preview], which renders
 * every message in memory without writing anything. That is deliberate: the
 * counts and the uniqueness meter this screen shows have to be the *real*
 * numbers for the exact strings that will go out, not an estimate, or the
 * anti-ban warnings are theatre.
 */
class CampaignComposerViewModel(
    private val application: Application,
    private val repository: CampaignRepository,
    listDao: ContactListDao,
    templateDao: TemplateDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState = MutableStateFlow(CampaignComposerUiState())
    val uiState: StateFlow<CampaignComposerUiState> = _uiState.asStateFlow()

    val lists: StateFlow<List<ContactListSummary>> = listDao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val templates: StateFlow<List<TemplateEntity>> = templateDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Held so a fast tap through three lists doesn't leave a stale preview behind. */
    private var previewJob: Job? = null

    init {
        refreshInstalledPackages()
    }

    /**
     * Which WhatsApp variants are on the phone. Cheap enough to re-read, and it
     * has to be re-readable because the user can leave, install WhatsApp
     * Business, and come back without this screen being recreated.
     */
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

    fun selectList(listId: Long) {
        if (_uiState.value.selectedListId == listId) return
        _uiState.update { it.copy(selectedListId = listId) }
        refreshPreview()
    }

    fun selectTemplate(templateId: Long) {
        if (_uiState.value.selectedTemplateId == templateId) return
        _uiState.update { it.copy(selectedTemplateId = templateId) }
        refreshPreview()
    }

    /**
     * The screen is responsible for showing the red warning before Fast can be
     * picked — see [PacingProfileCatalog.needsWarning]. By the time this is
     * called the user has already been told.
     */
    fun selectPacingProfile(name: String) {
        _uiState.update { it.copy(pacingProfile = name) }
    }

    fun selectPackage(target: WaPackage) {
        _uiState.update { it.copy(selectedPackage = target) }
    }

    fun selectSchedule(choice: ScheduleChoice) {
        _uiState.update { it.copy(schedule = choice) }
    }

    /**
     * Freezes the queue and hands it to the job runner.
     *
     * A campaign scheduled for later is created but *not* flipped to Running —
     * otherwise the schedule the user chose would be ignored and the blast
     * would go out immediately, which is exactly the surprise this screen
     * exists to prevent. The service is told about it either way; waiting for
     * the scheduled time and for the active-hours window is its job.
     */
    fun start() {
        val state = _uiState.value
        val listId = state.selectedListId ?: return
        val templateId = state.selectedTemplateId ?: return
        val target = state.selectedPackage ?: return
        if (state.starting) return

        _uiState.update { it.copy(starting = true) }
        viewModelScope.launch {
            val scheduledAt = state.schedule.startAtMillis(now(), zone)
            val id = repository.create(
                name = campaignNameFor(listId),
                listId = listId,
                templateId = templateId,
                pacingProfile = state.pacingProfile,
                waPackage = target.packageName,
                scheduledAt = scheduledAt
            )

            if (id == null) {
                _uiState.update {
                    it.copy(starting = false, message = NOBODY_LEFT)
                }
                return@launch
            }

            if (scheduledAt == null) repository.start(id)
            CampaignJobService.start(application, id)
            _uiState.update { it.copy(starting = false, startedCampaignId = id) }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    /** Cleared once the screen has navigated away, so a back-press can't re-fire it. */
    fun startHandled() {
        _uiState.update { it.copy(startedCampaignId = null) }
    }

    private fun refreshPreview() {
        val state = _uiState.value
        val listId = state.selectedListId
        val templateId = state.selectedTemplateId

        previewJob?.cancel()
        if (listId == null || templateId == null) {
            _uiState.update { it.copy(preview = null, previewing = false) }
            return
        }

        _uiState.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            val preview = repository.preview(listId, templateId)
            _uiState.update { it.copy(preview = preview, previewing = false) }
        }
    }

    /**
     * The repository falls back to the list name when this is blank, so this is
     * only ever a nicety — but a campaign named after its list is what makes the
     * activity log readable a month later.
     */
    private fun campaignNameFor(listId: Long): String =
        lists.value.firstOrNull { it.id == listId }?.name.orEmpty()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        private val NOBODY_LEFT =
            "Nobody on this list can be messaged right now — everyone on it is opted out, " +
                "blocked, or was messaged in the last " +
                "${RecipientOrdering.DEFAULT_COOLDOWN_DAYS} days."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                val database = ScopeWaDatabase.get(application)
                CampaignComposerViewModel(
                    application = application,
                    repository = CampaignRepository.create(application),
                    listDao = database.contactListDao(),
                    templateDao = database.templateDao()
                )
            }
        }
    }
}

private const val MILLIS_PER_MINUTE = 60_000L

/** Inside the 8am–8pm active-hours window from architecture doc section 6, layer 2. */
private const val MORNING_HOUR = 9
