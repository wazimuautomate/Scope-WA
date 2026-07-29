package com.tricreta.scopewa.accessibility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.tricreta.scopewa.brain.groupadd.AddCandidate
import com.tricreta.scopewa.brain.groupadd.GroupAddOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adds one person to a WhatsApp group by driving the real app — the "HANDS"
 * layer from architecture doc section 5.2, for the highest-risk action in the
 * product (section 6, layer 5).
 *
 * Mirrors [WaSender]'s structure deliberately: open, wait for the screen you
 * expect, act, then **verify rather than assume**. Three things differ, and all
 * three are consequences of there being no deep link for this:
 *
 * 1. **Text has to be typed.** [WaSender] prefills through `wa.me` precisely to
 *    avoid `ACTION_SET_TEXT` on a view WhatsApp owns. There is no equivalent
 *    URL for "open group X's add-participants screen", so this class types into
 *    the search fields. That is the single most fragile thing here.
 * 2. **The flow is six screens deep** — chat list → search → group → group info
 *    → add participants → confirm. Every rung is another selector that can be
 *    wrong, which is why [openGroupInfo] and [addOne] are separate: a failure
 *    reports *which* screen it died on.
 * 3. **Success is confirmed against the participant count**, the closest
 *    equivalent to [WaSender]'s "the compose box cleared". A count that didn't
 *    move, or couldn't be read, is reported as not-confirmed rather than
 *    optimistically as added — a group-add run that reports a clean 100% while
 *    adding nobody is worse than one that reports honestly.
 *
 * ⚠️ **Nothing in here has been run against a real phone.** Every selector it
 * uses is a researched candidate in [WaSelectors] (see the Phase 7 block there).
 * Expect to correct several from a Diagnostics dump before this works.
 *
 * Every WhatsApp-specific string lives in [WaSelectors] — never inline one here
 * (`CLAUDE.md`, and architecture doc section 8 for why).
 */
object WaGroupAdder {

    const val DEFAULT_APPEAR_TIMEOUT_MS = 20_000L
    const val DEFAULT_STEP_TIMEOUT_MS = 8_000L

    private const val POLL_INTERVAL_MS = 250L

    /** A beat between taps. Instant chains of taps are not what a thumb does. */
    private const val HUMAN_BEAT_MS = 700L

    private const val TAG = "WaGroupAdder"

    /** Where the group-add flow currently is, so failures can name the screen. */
    sealed interface GroupOpen {
        /** Group info is on screen; [participantCount] is null when unreadable. */
        data class Ready(val participantCount: Int?) : GroupOpen

        data class Failed(val outcome: GroupAddOutcome) : GroupOpen
    }

    /**
     * Brings WhatsApp to the front and navigates to [groupName]'s **group info**
     * screen, which is where [addOne] expects to start.
     *
     * Called once per batch rather than once per person: reopening the group
     * between every add is six more chances to fail and, more to the point,
     * looks nothing like a human adding three friends in a row.
     */
    suspend fun openGroupInfo(
        context: Context,
        target: WaPackage,
        groupName: String,
        appearTimeoutMillis: Long = DEFAULT_APPEAR_TIMEOUT_MS
    ): GroupOpen {
        if (!target.isInstalledOn(context)) {
            return GroupOpen.Failed(
                GroupAddOutcome.NotReady("${target.displayName} is not installed on this phone")
            )
        }
        if (!WaServiceBridge.isConnected.value) {
            return GroupOpen.Failed(
                GroupAddOutcome.NotReady("Accessibility service isn't connected — finish setup")
            )
        }
        if (groupName.isBlank()) {
            return GroupOpen.Failed(GroupAddOutcome.GroupNotReached("No group name was chosen"))
        }

        if (!launch(context, target)) {
            return GroupOpen.Failed(
                GroupAddOutcome.NotReady("Couldn't open ${target.displayName}")
            )
        }

        val appeared = awaitPackage(target, appearTimeoutMillis)
        if (!appeared) {
            return GroupOpen.Failed(
                GroupAddOutcome.NotReady("${target.displayName} never came to the front")
            )
        }
        restrictionNow()?.let { return GroupOpen.Failed(GroupAddOutcome.Restricted(it)) }

        // 1. Chat-list search → type the group name.
        if (!tap(target, WaSelectors.MainSearchButton)) {
            return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("Couldn't find WhatsApp's search button")
            )
        }
        delay(HUMAN_BEAT_MS)

        val searchField = await(target, WaSelectors.MainSearchField, DEFAULT_STEP_TIMEOUT_MS)
            ?: return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("Couldn't find WhatsApp's search box")
            )
        if (!setText(searchField.node, groupName)) {
            return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("WhatsApp wouldn't accept the group name being typed")
            )
        }
        delay(HUMAN_BEAT_MS)

        // 2. Tap the result whose title is the group. Matching on the exact
        //    name, not "the first row", because the first row of a WhatsApp
        //    search is often a message hit rather than the chat itself.
        val row = awaitTextNode(target, groupName, DEFAULT_STEP_TIMEOUT_MS)
            ?: return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("No chat called \"$groupName\" came back from search")
            )
        if (!clickNode(row)) {
            return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("Couldn't open the chat called \"$groupName\"")
            )
        }
        delay(HUMAN_BEAT_MS)

        // 3. Tap the conversation title to open group info.
        val title = await(target, WaSelectors.ConversationTitle, DEFAULT_STEP_TIMEOUT_MS)
            ?: return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("\"$groupName\" didn't open as a chat")
            )
        if (!clickNode(title.node)) {
            return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached("Couldn't open group info for \"$groupName\"")
            )
        }
        delay(HUMAN_BEAT_MS)

        restrictionNow()?.let { return GroupOpen.Failed(GroupAddOutcome.Restricted(it)) }

        // Group info is confirmed by the Add-participants control being there.
        // Without it there is nothing this class can do on that screen anyway.
        await(target, WaSelectors.AddParticipantsButton, DEFAULT_STEP_TIMEOUT_MS)
            ?: return GroupOpen.Failed(
                GroupAddOutcome.GroupNotReached(
                    "Opened \"$groupName\" but couldn't find Add participants — " +
                        "you may not be an admin of this group"
                )
            )

        return GroupOpen.Ready(participantCount())
    }

    /**
     * Adds one person, starting and finishing on the group info screen.
     *
     * @param countBefore the participant count read before this add, used to
     *   verify it. Null means it couldn't be read, in which case verification
     *   falls back to looking for the person in the participant list.
     */
    suspend fun addOne(
        target: WaPackage,
        candidate: AddCandidate,
        countBefore: Int?,
        stepTimeoutMillis: Long = DEFAULT_STEP_TIMEOUT_MS
    ): GroupAddOutcome {
        val startedAt = System.currentTimeMillis()

        if (!WaServiceBridge.isConnected.value) {
            return GroupAddOutcome.NotReady("Accessibility service isn't connected")
        }
        if (candidate.phoneE164.isBlank()) {
            return GroupAddOutcome.Skipped("No usable phone number")
        }
        restrictionNow()?.let { return GroupAddOutcome.Restricted(it) }

        // 1. Add participants → search field.
        if (!tap(target, WaSelectors.AddParticipantsButton)) {
            return GroupAddOutcome.GroupNotReached("Couldn't tap Add participants")
        }
        delay(HUMAN_BEAT_MS)

        val search = await(target, WaSelectors.AddParticipantSearch, stepTimeoutMillis)
            ?: return recover(GroupAddOutcome.GroupNotReached("The add-participants search box didn't appear"))

        if (!setText(search.node, candidate.phoneE164)) {
            return recover(GroupAddOutcome.NotConfirmed("WhatsApp wouldn't accept the number being typed"))
        }
        delay(HUMAN_BEAT_MS)

        // 2. Tap the matching contact row. WhatsApp is slow to search, so this
        //    waits rather than reading once.
        val result = await(target, WaSelectors.AddParticipantResultRow, stepTimeoutMillis)
        if (result == null) {
            // Classify before blaming the number: an "already in this group"
            // notice also leaves the result list empty.
            alreadyMemberNow()?.let { return recover(GroupAddOutcome.AlreadyInGroup(it)) }
            privacyBlockNow()?.let { return recover(GroupAddOutcome.NeedsInviteLink(it)) }
            return recover(GroupAddOutcome.NumberNotFound(candidate.phoneE164))
        }
        if (!clickNode(result.node)) {
            return recover(GroupAddOutcome.NotConfirmed("Couldn't tap ${candidate.label} in the results"))
        }
        delay(HUMAN_BEAT_MS)

        // 3. Confirm.
        if (!tap(target, WaSelectors.AddParticipantConfirm)) {
            return recover(GroupAddOutcome.NotConfirmed("Couldn't find the button that confirms the add"))
        }

        // 4. Classify whatever WhatsApp says back, before verifying.
        val verdict: GroupAddOutcome? = withTimeoutOrNull(stepTimeoutMillis) {
            var settled: GroupAddOutcome? = null
            while (settled == null) {
                val restriction = restrictionNow()
                val privacy = privacyBlockNow()
                val alreadyMember = alreadyMemberNow()
                settled = when {
                    restriction != null -> GroupAddOutcome.Restricted(restriction)
                    privacy != null -> GroupAddOutcome.NeedsInviteLink(privacy)
                    alreadyMember != null -> GroupAddOutcome.AlreadyInGroup(alreadyMember)
                    addLanded(candidate, countBefore) ->
                        GroupAddOutcome.Added(System.currentTimeMillis() - startedAt)
                    else -> null
                }
                if (settled == null) delay(POLL_INTERVAL_MS)
            }
            settled
        }

        return when (verdict) {
            null -> recover(
                GroupAddOutcome.NotConfirmed(
                    "The group's participant count didn't change — treating ${candidate.label} " +
                        "as not added"
                )
            )
            // A privacy block leaves a dialog on screen; clear it so the next
            // person in the batch doesn't start behind it.
            is GroupAddOutcome.NeedsInviteLink -> {
                dismissDialog(target)
                verdict
            }
            is GroupAddOutcome.Added -> verdict
            else -> recover(verdict)
        }
    }

    /**
     * The whole flow for one person, for callers that aren't batching. The job
     * service uses [openGroupInfo] once per batch and [addOne] per person
     * instead.
     */
    suspend fun add(
        context: Context,
        target: WaPackage,
        groupName: String,
        candidate: AddCandidate
    ): GroupAddOutcome = when (val opened = openGroupInfo(context, target, groupName)) {
        is GroupOpen.Failed -> opened.outcome
        is GroupOpen.Ready -> addOne(target, candidate, opened.participantCount)
    }

    /** The participant count on the group info screen right now, or null. */
    fun participantCount(): Int? =
        WaSelectors.participantCountIn(visibleText())

    // ---- verification ------------------------------------------------------

    /**
     * True when there is positive evidence the person is now in the group.
     *
     * Two independent signals, because either alone is unreliable: the count
     * moving up, or their name/number appearing on the participant list. A
     * *missing* count counts as no evidence, never as success — see the class
     * KDoc.
     */
    private fun addLanded(candidate: AddCandidate, countBefore: Int?): Boolean {
        val visible = visibleText()
        val countNow = WaSelectors.participantCountIn(visible)
        if (countBefore != null && countNow != null && countNow > countBefore) return true

        val needle = candidate.displayName.takeIf { it.isNotBlank() } ?: candidate.phoneE164
        return visible.contains(needle, ignoreCase = true)
    }

    // ---- screen reading ----------------------------------------------------

    private fun visibleText(): String =
        NodeFinder.collectVisibleText(WaServiceBridge.currentWindowRoot()).joinToString(" ")

    private fun restrictionNow(): String? =
        WaSelectors.matchFragment(visibleText(), WaSelectors.RESTRICTION_TEXT_FRAGMENTS)

    private fun alreadyMemberNow(): String? =
        WaSelectors.matchFragment(visibleText(), WaSelectors.ALREADY_MEMBER_TEXT_FRAGMENTS)

    /**
     * A privacy block, evidenced either by the wording or by WhatsApp offering
     * the invite-link button.
     *
     * [WaSelectors.PRIVACY_BLOCKED_TEXT_FRAGMENTS] includes the bare word
     * "invite", which is broad enough to fire on an unrelated "Invite to
     * WhatsApp" row — so this is only ever consulted *after* a confirm tap,
     * where the only thing that should be on screen is WhatsApp's answer.
     */
    private fun privacyBlockNow(): String? {
        val root = WaServiceBridge.currentWindowRoot() ?: return null
        val visible = NodeFinder.collectVisibleText(root).joinToString(" ")
        return WaSelectors.matchFragment(visible, WaSelectors.PRIVACY_BLOCKED_TEXT_FRAGMENTS)
    }

    // ---- primitives --------------------------------------------------------

    private suspend fun awaitPackage(target: WaPackage, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (WaServiceBridge.currentWindowPackage() != target.packageName) {
                delay(POLL_INTERVAL_MS)
            }
            true
        } ?: false

    private suspend fun await(
        target: WaPackage,
        selector: WaSelectors.Selector,
        timeoutMillis: Long
    ): NodeMatch? = withTimeoutOrNull(timeoutMillis) {
        var found: NodeMatch? = null
        while (found == null) {
            found = NodeFinder.find(
                WaServiceBridge.currentWindowRoot(),
                selector,
                target.packageName
            )
            if (found == null) delay(POLL_INTERVAL_MS)
        }
        found
    }

    /** Waits for a node whose text is exactly [text] — used to pick a chat by name. */
    private suspend fun awaitTextNode(
        target: WaPackage,
        text: String,
        timeoutMillis: Long
    ): AccessibilityNodeInfo? = withTimeoutOrNull(timeoutMillis) {
        var found: AccessibilityNodeInfo? = null
        while (found == null) {
            val root = WaServiceBridge.currentWindowRoot()
            if (root?.packageName?.toString() == target.packageName) {
                found = NodeFinder.firstMatching(root) { node ->
                    node.text?.toString()?.equals(text, ignoreCase = true) == true
                }
            }
            if (found == null) delay(POLL_INTERVAL_MS)
        }
        found
    }

    private suspend fun tap(target: WaPackage, selector: WaSelectors.Selector): Boolean {
        val match = await(target, selector, DEFAULT_STEP_TIMEOUT_MS) ?: return false
        return clickNode(match.node)
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        val clickable = NodeFinder.clickableSelfOrAncestor(node) ?: return false
        return runCatching {
            clickable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
        }.getOrElse {
            Log.w(TAG, "click threw", it)
            false
        }
    }

    /**
     * Types [value] into [node].
     *
     * `ACTION_SET_TEXT` on a view WhatsApp owns is exactly what [WaSender]
     * avoids, and for good reason — it breaks on layout changes. There is no
     * alternative here: no URL opens a group's add-participants screen with a
     * number prefilled.
     */
    private fun setText(node: AccessibilityNodeInfo?, value: String): Boolean {
        val field = node ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return runCatching {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS.id)
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id, arguments)
        }.getOrElse {
            Log.w(TAG, "setText threw", it)
            false
        }
    }

    private suspend fun dismissDialog(target: WaPackage) {
        if (!tap(target, WaSelectors.DialogDismissButton)) WaServiceBridge.pressBack()
        delay(HUMAN_BEAT_MS)
    }

    /**
     * Backs out to the group info screen and returns [outcome] unchanged.
     *
     * Leaving WhatsApp sitting on a half-typed search box would make the *next*
     * person in the batch fail too — and the failure breaker is only two deep,
     * so one stranded screen would end the whole run.
     */
    private suspend fun recover(outcome: GroupAddOutcome): GroupAddOutcome {
        WaServiceBridge.pressBack()
        delay(HUMAN_BEAT_MS)
        return outcome
    }

    private fun launch(context: Context, target: WaPackage): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
        if (intent == null) {
            false
        } else {
            context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            true
        }
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no launch activity for ${target.packageName}", e)
        false
    }
}
