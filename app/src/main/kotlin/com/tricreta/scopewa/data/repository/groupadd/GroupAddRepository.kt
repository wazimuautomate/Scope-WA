package com.tricreta.scopewa.data.repository.groupadd

import android.content.Context
import com.tricreta.scopewa.brain.groupadd.AddCandidate
import com.tricreta.scopewa.brain.groupadd.AddProvenance
import com.tricreta.scopewa.brain.groupadd.EligibilitySplit
import com.tricreta.scopewa.brain.groupadd.GroupAddEligibility
import com.tricreta.scopewa.brain.groupadd.GroupAddOutcome
import com.tricreta.scopewa.data.db.ScopeWaDatabase
import com.tricreta.scopewa.data.db.dao.ContactDao
import com.tricreta.scopewa.data.db.dao.GroupAddJobDao
import com.tricreta.scopewa.data.db.entity.ContactEntity
import com.tricreta.scopewa.data.db.entity.GroupAddJobEntity
import com.tricreta.scopewa.data.db.entity.GroupAddStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/**
 * Everything Phase 7 needs from storage, in one place.
 *
 * The interesting method is [screen]: it is where architecture doc section 6
 * layer 5's "never cold numbers" rule meets the actual contact rows, by reading
 * provenance off the contact rather than trusting the user's selection. A
 * contact's provenance is derived, never entered by hand — otherwise the safety
 * rule would be an honour system.
 */
class GroupAddRepository(
    private val jobDao: GroupAddJobDao,
    private val contactDao: ContactDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis
) {

    // ---- screening ---------------------------------------------------------

    /**
     * Where a contact's right to be added comes from.
     *
     * - `times_replied > 0` — they have messaged the user. Phase 5 maintains it.
     * - `source_group` — Phase 4's extractor stamps the group a contact was
     *   pulled from, which is precisely "a list he extracted from a group they
     *   already joined" in section 6 layer 5's words.
     * - anything else is [AddProvenance.Cold], and cold is never addable.
     */
    fun provenanceOf(contact: ContactEntity): AddProvenance {
        val sourceGroup = contact.sourceGroup
        return when {
            contact.timesReplied > 0 -> AddProvenance.RepliedBefore
            !sourceGroup.isNullOrBlank() -> AddProvenance.SharedGroup(sourceGroup)
            else -> AddProvenance.Cold
        }
    }

    fun candidateOf(contact: ContactEntity): AddCandidate = AddCandidate(
        phoneE164 = contact.phoneE164,
        displayName = contact.displayName,
        provenance = provenanceOf(contact),
        contactId = contact.id
    )

    /**
     * Screens every contact on [listId] against the eligibility rule.
     *
     * Ordered strongest-provenance-first, so that when the daily cap of 20 cuts
     * a longer list short, the people who actually got added are the safest
     * ones — the same reasoning as Phase 5's recipient ordering (`MEMORY.md`).
     */
    suspend fun screen(listId: Long): EligibilitySplit {
        val contacts = contactDao.allInList(listId)
        val optedOut = contactDao.optedOutNumbers().toSet()
        val candidates = contacts
            .map(::candidateOf)
            .sortedByDescending { it.provenance == AddProvenance.RepliedBefore }
        return GroupAddEligibility.partition(candidates, optedOut)
    }

    // ---- jobs --------------------------------------------------------------

    suspend fun create(
        targetGroup: String,
        sourceListId: Long?,
        waPackage: String,
        split: EligibilitySplit
    ): Long? {
        if (targetGroup.isBlank() || split.hasNobody) return null

        val at = now()
        val job = GroupAddJobEntity(
            targetGroup = targetGroup.trim(),
            sourceListId = sourceListId,
            waPackage = waPackage,
            status = GroupAddStatus.Draft.name,
            pending = split.eligible.map { it.phoneE164 },
            names = split.eligible.associate { it.phoneE164 to it.displayName },
            provenance = split.eligible.associate { it.phoneE164 to it.provenance.code },
            // Kept on the row so the cold rejections stay auditable after the
            // fact. A safety feature nobody can check afterwards isn't one.
            rejectedCold = split.rejected
                .filter { it.isCold }
                .associate { it.candidate.phoneE164 to it.reason },
            skippedColdCount = split.coldCount,
            dayStamp = today(),
            createdAt = at,
            updatedAt = at
        )
        return jobDao.insert(job)
    }

    suspend fun job(id: Long): GroupAddJobEntity? = jobDao.byId(id)

    /**
     * Rebuilds the still-pending queue as candidates the planner understands.
     *
     * Provenance round-trips through [AddProvenance.fromCode], which decodes
     * anything it doesn't recognise as [AddProvenance.Cold] — so a corrupted
     * row loses people from the queue rather than quietly promoting them to
     * addable.
     */
    fun queuedCandidates(job: GroupAddJobEntity): List<AddCandidate> =
        job.pending.map { number ->
            AddCandidate(
                phoneE164 = number,
                displayName = job.names[number].orEmpty(),
                provenance = AddProvenance.fromCode(job.provenance[number])
            )
        }

    fun observeJob(id: Long): Flow<GroupAddJobEntity?> = jobDao.observeById(id)

    fun observeActive(): Flow<GroupAddJobEntity?> = jobDao.observeActiveJob()

    suspend fun activeJob(): GroupAddJobEntity? = jobDao.activeJob()

    /**
     * Adds made today across **every** job on this phone number — what the daily
     * cap of 20 is checked against. See [GroupAddJobDao.addedOn].
     */
    suspend fun addedToday(): Int = jobDao.addedOn(today())

    suspend fun start(id: Long) {
        val at = now()
        jobDao.markStarted(id, at)
        jobDao.setStatus(id, GroupAddStatus.Running.name, null, at)
    }

    suspend fun pause(id: Long, reason: String?) {
        jobDao.setStatus(id, GroupAddStatus.Paused.name, reason, now())
    }

    suspend fun finish(id: Long, reason: String?) {
        val at = now()
        jobDao.setStatus(id, GroupAddStatus.Finished.name, reason, at)
        jobDao.markFinished(id, at)
    }

    suspend fun stop(id: Long, reason: String?) {
        val at = now()
        jobDao.setStatus(id, GroupAddStatus.Stopped.name, reason, at)
        jobDao.markFinished(id, at)
    }

    /**
     * Re-orders the queue to the plan the planner produced, and records who the
     * daily cap deferred. Called once, just before the run starts.
     */
    suspend fun setQueue(id: Long, ordered: List<String>) {
        val job = jobDao.byId(id) ?: return
        jobDao.update(job.copy(pending = ordered, updatedAt = now()))
    }

    /**
     * Files one outcome into the right bucket and moves the person out of the
     * queue.
     *
     * Written after every single add rather than at the end, so being killed
     * mid-run costs at most the one person in flight — and so that a
     * [GroupAddOutcome.NeedsInviteLink] is durable the moment it happens. That
     * bucket is terminal: the number leaves `pending` and nothing ever puts it
     * back (architecture doc section 8).
     */
    suspend fun record(jobId: Long, number: String, outcome: GroupAddOutcome) {
        val job = jobDao.byId(jobId) ?: return
        val at = now()
        val stamp = today()

        // The persisted daily counter is scoped to a day stamp; a run that
        // crosses midnight starts a fresh allowance rather than inheriting
        // yesterday's.
        val rolled = if (job.dayStamp == stamp) job else job.copy(addedToday = 0, dayStamp = stamp)

        val updated = when (outcome) {
            is GroupAddOutcome.Added -> rolled.copy(
                added = rolled.added + number,
                addedCount = rolled.addedCount + 1,
                addedToday = rolled.addedToday + 1
            )

            is GroupAddOutcome.NeedsInviteLink -> rolled.copy(
                needsInvite = rolled.needsInvite + number,
                needsInviteCount = rolled.needsInviteCount + 1
            )

            is GroupAddOutcome.AlreadyInGroup, is GroupAddOutcome.Skipped -> rolled.copy(
                skipped = rolled.skipped + (number to outcome.describe()),
                skippedCount = rolled.skippedCount + 1
            )

            else -> rolled.copy(
                failed = rolled.failed + (number to outcome.describe()),
                failedCount = rolled.failedCount + 1,
                lastFailure = outcome.describe()
            )
        }

        jobDao.update(
            updated.copy(
                pending = updated.pending.filterNot { it == number },
                updatedAt = at
            )
        )
    }

    /** Puts people the cap deferred back at the head of tomorrow's queue. */
    suspend fun deferAll(jobId: Long, numbers: List<String>) {
        if (numbers.isEmpty()) return
        val job = jobDao.byId(jobId) ?: return
        val kept = numbers.filter { it in job.pending }
        jobDao.update(job.copy(pending = kept + job.pending.filterNot { it in kept }, updatedAt = now()))
    }

    // ---- export ------------------------------------------------------------

    /**
     * The invite bucket as shareable text.
     *
     * Deliberately a *message to send by hand*, not a retry list. WhatsApp's
     * privacy setting is the recipient's decision; the only honest next step is
     * for the client to send them a link himself.
     */
    fun inviteExport(job: GroupAddJobEntity): String = buildString {
        appendLine("Needs an invite link — ${job.targetGroup}")
        appendLine(
            "These ${job.needsInvite.size} people can't be added automatically because of their " +
                "WhatsApp privacy settings. That is their choice, not an error, and retrying " +
                "will never work. Send them the group's invite link instead."
        )
        appendLine()
        job.needsInvite.forEach { number ->
            appendLine("${job.labelFor(number)}\t$number")
        }
    }

    private fun today(): String =
        Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().toString()

    companion object {
        fun create(context: Context): GroupAddRepository {
            val database = ScopeWaDatabase.get(context.applicationContext)
            return GroupAddRepository(
                jobDao = database.groupAddJobDao(),
                contactDao = database.contactDao()
            )
        }
    }
}
