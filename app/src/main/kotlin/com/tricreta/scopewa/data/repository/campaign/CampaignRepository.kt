package com.tricreta.scopewa.data.repository.campaign

import android.content.Context
import androidx.room.withTransaction
import com.tricreta.scopewa.brain.campaign.OptOutDetector
import com.tricreta.scopewa.brain.campaign.RecipientCandidate
import com.tricreta.scopewa.brain.campaign.RecipientOrdering
import com.tricreta.scopewa.brain.campaign.RecipientPlan
import com.tricreta.scopewa.brain.campaign.RecipientVariables
import com.tricreta.scopewa.brain.pacing.WarmUpRamp
import com.tricreta.scopewa.brain.phone.PhoneNormalizer
import com.tricreta.scopewa.brain.reply.InFlightRecipient
import com.tricreta.scopewa.brain.reply.IncomingReply
import com.tricreta.scopewa.brain.reply.ReplyRoute
import com.tricreta.scopewa.brain.reply.ReplyRouter
import com.tricreta.scopewa.brain.template.TemplateEngine
import com.tricreta.scopewa.brain.template.TemplateVariables
import com.tricreta.scopewa.brain.uniqueness.UniquenessResult
import com.tricreta.scopewa.brain.uniqueness.UniquenessScorer
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.CampaignDao
import com.tricreta.scopewa.data.db.dao.CampaignProgress
import com.tricreta.scopewa.data.db.dao.ContactDao
import com.tricreta.scopewa.data.db.dao.ContactListDao
import com.tricreta.scopewa.data.db.dao.SuppressionDao
import com.tricreta.scopewa.data.db.dao.TemplateDao
import com.tricreta.scopewa.data.db.entity.CampaignEntity
import com.tricreta.scopewa.data.db.entity.CampaignMessageEntity
import com.tricreta.scopewa.data.db.entity.CampaignStatus
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.MessageStatus
import com.tricreta.scopewa.data.db.entity.SuppressionEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What a campaign would do, worked out before anything is written — the Start
 * button's preview. Mirrors Phase 2's import preview for the same reason:
 * telling the client what happened to 800 recipients afterwards is much worse
 * than telling them first.
 */
data class CampaignPreview(
    val plan: RecipientPlan = RecipientPlan(),
    val renderedSamples: List<String> = emptyList(),
    val uniqueness: UniquenessResult = UniquenessResult(0, 0, emptyList()),
    val dailyCap: Int = 0,
    val warmUpDay: Int = 1,
    val sentToday: Int = 0
) {
    /** Recipients beyond today's cap won't be reached today — say so up front. */
    val overDailyCap: Int get() = (plan.queuedCount - (dailyCap - sentToday)).coerceAtLeast(0)
    val canStart: Boolean get() = plan.queuedCount > 0
}

/**
 * The only thing the campaign UI and the foreground service talk to.
 *
 * Messages are rendered **when the queue is built**, not at send time. That is
 * what makes the uniqueness meter honest — it scores the exact strings that
 * will go out, spintax rolls and all, rather than an estimate — and it means a
 * campaign resumed after a reboot sends what the user previewed.
 */
class CampaignRepository(
    private val database: ScopeWaDatabase,
    private val campaignDao: CampaignDao,
    private val contactDao: ContactDao,
    private val listDao: ContactListDao,
    private val templateDao: TemplateDao,
    private val suppressionDao: SuppressionDao,
    private val engine: TemplateEngine = TemplateEngine(),
    private val warmUpRamp: WarmUpRamp = WarmUpRamp(),
    private val normalizer: PhoneNormalizer = PhoneNormalizer(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis
) {

    // ---- reads -------------------------------------------------------------

    fun observeCampaigns(): Flow<List<CampaignEntity>> = campaignDao.observeAll()

    fun observeCampaign(id: Long): Flow<CampaignEntity?> = campaignDao.observeById(id)

    fun observeActiveCampaign(): Flow<CampaignEntity?> = campaignDao.observeActiveCampaign()

    fun observeProgress(id: Long): Flow<CampaignProgress> = campaignDao.observeProgress(id)

    fun observeMessages(id: Long): Flow<List<CampaignMessageEntity>> = campaignDao.observeMessages(id)

    suspend fun campaign(id: Long): CampaignEntity? = campaignDao.byId(id)

    suspend fun progress(id: Long): CampaignProgress = campaignDao.progress(id)

    // ---- preview -----------------------------------------------------------

    /**
     * Builds the plan and renders every message *in memory*, so the composer can
     * show real counts and a real uniqueness score without committing anything.
     */
    suspend fun preview(listId: Long, templateId: Long): CampaignPreview {
        val template = templateDao.findById(templateId) ?: return CampaignPreview()
        val recipients = candidatesFor(listId)
        val plan = RecipientOrdering.plan(
            candidates = recipients,
            suppressedNumbers = suppressedNumbers(),
            nowMillis = now(),
            cooldownDays = RecipientOrdering.DEFAULT_COOLDOWN_DAYS
        )

        val rendered = renderAll(plan.queue, template.body)
        val warmUpDay = currentWarmUpDay()

        return CampaignPreview(
            plan = plan,
            renderedSamples = rendered.take(PREVIEW_SAMPLES),
            uniqueness = UniquenessScorer.score(rendered),
            dailyCap = warmUpRamp.dailyCapFor(warmUpDay),
            warmUpDay = warmUpDay,
            sentToday = sentToday()
        )
    }

    // ---- create ------------------------------------------------------------

    /**
     * Freezes the queue: ordering, rendered text and all. Returns the new
     * campaign id, or null when nobody is left to message.
     */
    suspend fun create(
        name: String,
        listId: Long,
        templateId: Long,
        pacingProfile: String,
        waPackage: String,
        scheduledAt: Long? = null,
        activeHoursStart: Int = DEFAULT_ACTIVE_HOURS_START,
        activeHoursEnd: Int = DEFAULT_ACTIVE_HOURS_END
    ): Long? {
        val template = templateDao.findById(templateId) ?: return null
        val recipients = candidatesFor(listId)
        val plan = RecipientOrdering.plan(
            candidates = recipients,
            suppressedNumbers = suppressedNumbers(),
            nowMillis = now(),
            cooldownDays = RecipientOrdering.DEFAULT_COOLDOWN_DAYS
        )
        if (plan.queue.isEmpty()) return null

        val rendered = renderAll(plan.queue, template.body)
        val timestamp = now()
        val listName = listDao.byId(listId)?.name.orEmpty()

        var campaignId: Long? = null
        database.withTransaction {
            val id = campaignDao.insert(
                CampaignEntity(
                    name = name.trim().ifBlank { listName.ifBlank { "Campaign" } },
                    listId = listId,
                    templateId = templateId,
                    pacingProfile = pacingProfile,
                    waPackage = waPackage,
                    scheduledAt = scheduledAt,
                    status = if (scheduledAt != null) CampaignStatus.Scheduled.name else CampaignStatus.Draft.name,
                    activeHoursStart = activeHoursStart,
                    activeHoursEnd = activeHoursEnd,
                    warmUpDay = currentWarmUpDay(),
                    sentTodayEpochDay = epochDay(timestamp),
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
            )

            val queued = plan.queue.mapIndexed { index, recipient ->
                CampaignMessageEntity(
                    campaignId = id,
                    contactId = recipient.contactId,
                    phoneE164 = recipient.phoneE164,
                    displayName = recipient.displayName,
                    renderedText = rendered[index],
                    status = MessageStatus.Pending.name,
                    orderIndex = index
                )
            }

            // Everyone deliberately left out is recorded too, so the report
            // explains the difference between "list of 824" and "sent 790".
            val skipped = plan.skipped.mapIndexed { index, entry ->
                CampaignMessageEntity(
                    campaignId = id,
                    contactId = entry.candidate.contactId,
                    phoneE164 = entry.candidate.phoneE164,
                    displayName = entry.candidate.displayName,
                    renderedText = "",
                    status = MessageStatus.Skipped.name,
                    orderIndex = queued.size + index,
                    error = describeSkip(entry.reason)
                )
            }

            (queued + skipped).chunked(CHUNK).forEach { campaignDao.insertMessages(it) }
            campaignId = id
        }
        return campaignId
    }

    // ---- control -----------------------------------------------------------

    suspend fun start(id: Long) {
        val timestamp = now()
        campaignDao.setStatus(id, CampaignStatus.Running.name, null, timestamp)
        campaignDao.markStarted(id, timestamp)
    }

    suspend fun pause(id: Long, reason: String?) =
        campaignDao.setStatus(id, CampaignStatus.Paused.name, reason, now())

    suspend fun resume(id: Long) =
        campaignDao.setStatus(id, CampaignStatus.Running.name, null, now())

    /** Deliberate, final, and not resumable — distinct from a circuit-breaker pause. */
    suspend fun stop(id: Long) {
        val timestamp = now()
        campaignDao.setStatus(id, CampaignStatus.Stopped.name, null, timestamp)
        campaignDao.markFinished(id, timestamp)
    }

    suspend fun complete(id: Long) {
        val timestamp = now()
        campaignDao.setStatus(id, CampaignStatus.Completed.name, null, timestamp)
        campaignDao.markFinished(id, timestamp)
    }

    suspend fun delete(id: Long) = campaignDao.deleteById(id)

    // ---- the send loop's view ---------------------------------------------

    suspend fun nextPending(campaignId: Long): CampaignMessageEntity? =
        campaignDao.nextPending(campaignId)

    suspend fun pendingCount(campaignId: Long): Int = campaignDao.pendingCount(campaignId)

    /**
     * Records a delivered message and advances everything that depends on it:
     * the contact's `last_messaged_at` (which drives the per-person cooldown)
     * and the campaign's restart-surviving daily counter.
     */
    suspend fun recordSent(message: CampaignMessageEntity) {
        val timestamp = now()
        database.withTransaction {
            campaignDao.updateMessage(
                message.copy(
                    status = MessageStatus.Sent.name,
                    sentAt = timestamp,
                    error = null,
                    attemptCount = message.attemptCount + 1
                )
            )
            contactDao.markMessaged(message.contactId, timestamp)
            bumpDailyCounter(message.campaignId, timestamp)
        }
    }

    suspend fun recordFailed(message: CampaignMessageEntity, error: String) {
        campaignDao.updateMessage(
            message.copy(
                status = MessageStatus.Failed.name,
                error = error,
                attemptCount = message.attemptCount + 1
            )
        )
    }

    suspend fun recordSkipped(message: CampaignMessageEntity, reason: String) {
        campaignDao.updateMessage(
            message.copy(status = MessageStatus.Skipped.name, error = reason)
        )
    }

    /**
     * Applies an opt-out reply — section 6 layer 3. Marks the contact, adds the
     * number to the suppression list so the block survives a delete-and-
     * reimport, and drops anything still queued for that person in any campaign.
     */
    suspend fun applyOptOut(phoneE164: String, replyText: String): Boolean {
        val keyword = OptOutDetector.matchedKeyword(replyText) ?: return false
        return applyOptOutForKeyword(phoneE164, keyword)
    }

    /**
     * The same thing as [applyOptOut], for callers that already ran
     * [OptOutDetector] and should not be passing a message body any further —
     * the reply listener path. Keeps the promise that notification text never
     * travels past the point where the yes/no decision was made.
     */
    suspend fun applyOptOutForKeyword(phoneE164: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val timestamp = now()
        val reason = OptOutDetector.reasonFor(keyword)
        database.withTransaction {
            suppressionDao.add(SuppressionEntity(phoneE164, reason, timestamp))
            contactDao.optOutByNumber(phoneE164, reason, timestamp)
            campaignDao.skipPendingForNumber(phoneE164, reason)
        }
        return true
    }

    // ---- incoming replies --------------------------------------------------

    /**
     * The whole reply pipeline behind one call, so
     * [com.tricreta.scopewa.accessibility.WaNotificationListener] can stay a
     * field-lifter with no logic in it.
     *
     * The routing decision itself is made by [ReplyRouter], which is pure and
     * unit tested; this method only supplies the in-flight recipient list and
     * writes the outcome. Returns the route it took so a caller (or a test)
     * can see what happened without the listener having to re-derive it.
     */
    suspend fun handleIncomingReply(reply: IncomingReply): ReplyRoute {
        val route = ReplyRouter.route(reply, inFlightRecipients(), normalizer)
        when (route) {
            is ReplyRoute.MarkOptOut -> applyOptOutForKeyword(route.phoneE164, route.matchedKeyword)
            is ReplyRoute.RecordReply -> recordReply(route.phoneE164)
            is ReplyRoute.Ignore -> Unit
        }
        return route
    }

    /**
     * Everyone messaged recently enough that a reply could still be about it.
     * The window is generous on purpose: a reply two days late is still a reply,
     * and the cost of a wide window is only that more names are candidates for
     * matching.
     */
    suspend fun inFlightRecipients(atMillis: Long = now()): List<InFlightRecipient> =
        campaignDao.messagedSince(atMillis - REPLY_WINDOW_MILLIS)
            .map { InFlightRecipient(phoneE164 = it.phoneE164, displayName = it.displayName) }

    /**
     * Records that [phoneE164] answered: against the contact (which promotes
     * them in [RecipientOrdering]'s replied-first tier) and against the most
     * recent message sent to them (which is what the cold-batch breaker counts).
     * The reply's text is not stored.
     */
    suspend fun recordReply(phoneE164: String) {
        val timestamp = now()
        database.withTransaction {
            contactDao.recordReply(phoneE164, timestamp)
            campaignDao.recordReplyForNumber(phoneE164, timestamp)
        }
    }

    /** Replies to [campaignId] since [since] — the current batch's reply count. */
    suspend fun repliesSince(campaignId: Long, since: Long): Int =
        campaignDao.replyCountSince(campaignId, since)

    /** Numbers the send loop must never dial: explicit blocks plus opt-outs. */
    suspend fun suppressedNumbers(): Set<String> =
        (suppressionDao.allNumbers() + contactDao.optedOutNumbers()).toSet()

    // ---- caps and warm-up --------------------------------------------------

    /**
     * Messages sent by *any* campaign today. The cap in section 6 layer 2 is a
     * property of the phone number, so three campaigns in one day share one
     * allowance rather than each getting a fresh one.
     */
    suspend fun sentToday(atMillis: Long = now()): Int {
        val startOfDay = startOfDayMillis(atMillis)
        return campaignDao.sentBetween(startOfDay, startOfDay + MILLIS_PER_DAY)
    }

    /** 1-based days since the very first send. A number that has never sent is on day 1. */
    suspend fun currentWarmUpDay(atMillis: Long = now()): Int {
        val first = campaignDao.firstSendAt() ?: return 1
        val days = epochDay(atMillis) - epochDay(first)
        return (days + 1).coerceAtLeast(1L).toInt()
    }

    suspend fun dailyCap(atMillis: Long = now()): Int =
        warmUpRamp.dailyCapFor(currentWarmUpDay(atMillis))

    private suspend fun bumpDailyCounter(campaignId: Long, atMillis: Long) {
        val campaign = campaignDao.byId(campaignId) ?: return
        val today = epochDay(atMillis)
        val next = if (campaign.sentTodayEpochDay == today) campaign.sentToday + 1 else 1
        campaignDao.setDailyCounter(campaignId, next, today, atMillis)
    }

    // ---- helpers -----------------------------------------------------------

    private suspend fun candidatesFor(listId: Long): List<RecipientCandidate> =
        contactDao.allInList(listId).map { it.toCandidate() }

    private fun ContactEntity.toCandidate() = RecipientCandidate(
        contactId = id,
        phoneE164 = phoneE164,
        displayName = displayName,
        isSaved = isSaved,
        timesReplied = timesReplied,
        optedOut = optedOut,
        lastMessagedAt = lastMessagedAt,
        fields = customFields
    )

    private fun renderAll(recipients: List<RecipientCandidate>, body: String): List<String> {
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now()), zone)
        val automatic = TemplateVariables.automaticValues(localNow)
        val known = RecipientVariables.knownNames(recipients, automatic.keys)
        return recipients.map { recipient ->
            engine.render(
                template = body,
                variables = RecipientVariables.forRecipient(recipient, automatic),
                knownVariableNames = known
            )
        }
    }

    private fun describeSkip(reason: com.tricreta.scopewa.brain.campaign.SkipReason): String =
        when (reason) {
            com.tricreta.scopewa.brain.campaign.SkipReason.OptedOut ->
                "Opted out or blocked — never messaged"
            com.tricreta.scopewa.brain.campaign.SkipReason.WithinCooldown ->
                "Messaged in the last ${RecipientOrdering.DEFAULT_COOLDOWN_DAYS} days"
            com.tricreta.scopewa.brain.campaign.SkipReason.DuplicateInList ->
                "Duplicate number in the list"
        }

    private fun startOfDayMillis(atMillis: Long): Long =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()

    private fun epochDay(atMillis: Long): Long =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toEpochDay()

    companion object {
        const val DEFAULT_ACTIVE_HOURS_START = 8
        const val DEFAULT_ACTIVE_HOURS_END = 20
        const val PREVIEW_SAMPLES = 5

        private const val CHUNK = 400
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** How long after being messaged somebody's reply still counts as a reply. */
        const val REPLY_WINDOW_DAYS = 14L
        private const val REPLY_WINDOW_MILLIS = REPLY_WINDOW_DAYS * MILLIS_PER_DAY

        fun create(context: Context): CampaignRepository {
            val database = ScopeWaDatabase.get(context)
            return CampaignRepository(
                database = database,
                campaignDao = database.campaignDao(),
                contactDao = database.contactDao(),
                listDao = database.contactListDao(),
                templateDao = database.templateDao(),
                suppressionDao = database.suppressionDao()
            )
        }
    }
}
