package com.tricreta.scopewa.accessibility

/**
 * Every WhatsApp-specific view-id, text, and content-description in the app
 * lives in this one file, with ordered fallbacks — architecture doc sections
 * 5.1 and 8. When WhatsApp changes its UI, this is the only file that should
 * need a patch, and that patch ships as a point release through the in-app
 * updater.
 *
 * Deliberately contains **no Android imports** so the matching rules can be
 * unit tested in CI without a device.
 *
 * ## Read this before trusting any candidate below
 *
 * The view-ids here are the ones WhatsApp has used publicly for years and
 * that community automation projects rely on. They are **starting
 * candidates, not verified facts** — they have not yet been confirmed against
 * the client's actual phones and WhatsApp versions. Use the in-app
 * **Diagnostics → Dump WhatsApp screen** tool to capture the real ids from a
 * device and correct anything wrong here. Every selector is a *list* of
 * candidates tried in order precisely so that a wrong guess degrades to the
 * next fallback instead of breaking outright.
 *
 * ## Ordering rules
 *
 * 1. **View-ids first.** They are stable across locales and are the only
 *    candidate type safe to match exactly.
 * 2. **Content-descriptions and visible text last, and treat them as
 *    best-effort.** They are localised — a phone set to Swahili will not say
 *    "Type a message". Never let a text match be the *only* way to find
 *    something that matters.
 */
object WaSelectors {

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    val SUPPORTED_PACKAGES = listOf(PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS)

    /**
     * One UI element, described as ordered fallback candidates.
     *
     * @param name human-readable label, used in diagnostics output so a failed
     *   match reads as "couldn't find the compose box" rather than an id dump.
     * @param viewIds unqualified resource-entry names (e.g. `entry`), qualified
     *   at match time with whichever WhatsApp package is in use.
     * @param contentDescriptions localised, best-effort. See ordering rules above.
     * @param texts localised, best-effort. See ordering rules above.
     * @param className expected widget class, used to disambiguate when several
     *   nodes match — not required to match on its own.
     */
    data class Selector(
        val name: String,
        val viewIds: List<String> = emptyList(),
        val contentDescriptions: List<String> = emptyList(),
        val texts: List<String> = emptyList(),
        val className: String? = null
    ) {
        /** Fully-qualified `pkg:id/entry` forms, in fallback order. */
        fun qualifiedViewIds(packageName: String): List<String> =
            viewIds.map { "$packageName:id/$it" }

        /** True when this selector has at least one locale-independent way to match. */
        val hasViewIdCandidates: Boolean get() = viewIds.isNotEmpty()
    }

    // ---------------------------------------------------------------------
    // Conversation screen — needed by Phase 1's probe and Phase 5's sender.
    // ---------------------------------------------------------------------

    /** The message input box on a conversation screen. */
    val ComposeBox = Selector(
        name = "compose box",
        viewIds = listOf("entry", "entry_text"),
        contentDescriptions = listOf("Type a message", "Message"),
        className = "android.widget.EditText"
    )

    /** The send (paper-plane) button. Only present once the compose box is non-empty. */
    val SendButton = Selector(
        name = "send button",
        viewIds = listOf("send", "send_container"),
        contentDescriptions = listOf("Send")
    )

    /** Contact/group name in the conversation toolbar — used to confirm the right chat is open. */
    val ConversationTitle = Selector(
        name = "conversation title",
        viewIds = listOf("conversation_contact_name")
    )

    // ---------------------------------------------------------------------
    // Group info / participants — Phase 4 (extractor) and Phase 7 (adder).
    // Listed now so all WhatsApp knowledge stays in one file from day one;
    // these are the least verified of the lot.
    // ---------------------------------------------------------------------

    /** The scrollable participant list on a group info screen. */
    val ParticipantList = Selector(
        name = "participant list",
        viewIds = listOf("participants_list", "list"),
        className = "androidx.recyclerview.widget.RecyclerView"
    )

    /** A single participant row's display name within [ParticipantList]. */
    val ParticipantName = Selector(
        name = "participant name",
        viewIds = listOf("name", "contactpicker_row_name")
    )

    /** A participant row's subtitle — the phone number, or the "about" text. */
    val ParticipantSubtitle = Selector(
        name = "participant subtitle",
        viewIds = listOf("status", "contactpicker_row_status")
    )

    /** The search field on the "Add participants" screen. */
    val AddParticipantSearch = Selector(
        name = "add-participant search field",
        viewIds = listOf("search_src_text", "menuitem_search"),
        contentDescriptions = listOf("Search")
    )

    // ---------------------------------------------------------------------
    // Group adding — Phase 7. Appended, per docs/BUILD-PLAN.md's shared-hotspot
    // rule ("append, don't restructure").
    //
    // ⚠️ **None of these has ever been seen on a real device.** They are
    // researched candidates drawn from the ids WhatsApp has used publicly and
    // that community automation projects rely on — the same standing as the
    // Phase 1 selectors above, and no better. The group-add flow is six screens
    // deep, so it has more chances to be wrong than the send path does. Capture
    // the real ids with Diagnostics → Dump WhatsApp screen before trusting any
    // of it, and expect to correct several.
    // ---------------------------------------------------------------------

    /** The magnifier on WhatsApp's chat list — the way into finding a group by name. */
    val MainSearchButton = Selector(
        name = "chat-list search button",
        viewIds = listOf("menuitem_search", "search_button"),
        contentDescriptions = listOf("Search")
    )

    /** The text field the group name gets typed into, on the chat list or the picker. */
    val MainSearchField = Selector(
        name = "chat-list search field",
        viewIds = listOf("search_src_text", "search_input"),
        className = "android.widget.EditText"
    )

    /** A row title in the chat list or a search result list. */
    val ChatListRowTitle = Selector(
        name = "chat-list row title",
        viewIds = listOf("conversations_row_contact_name", "contact_name", "conversation_contact_name")
    )

    /**
     * The "Add participants" entry on a group info screen. WhatsApp has shipped
     * this both as a dedicated button and as the first row of the participant
     * list, hence the spread of candidates.
     */
    val AddParticipantsButton = Selector(
        name = "add participants button",
        viewIds = listOf("add_participant_button", "add_participants", "menuitem_add_people", "add_people"),
        contentDescriptions = listOf("Add participants", "Add members"),
        texts = listOf("Add participants", "Add members", "Add participant")
    )

    /** A tappable row in the add-participants search results. */
    val AddParticipantResultRow = Selector(
        name = "add-participant result row",
        viewIds = listOf("contactpicker_row_name", "name", "chat_able_contacts_row_name")
    )

    /** The tick / Next / OK that commits the selected people to the group. */
    val AddParticipantConfirm = Selector(
        name = "add-participant confirm button",
        viewIds = listOf("ok_btn", "next_btn", "menuitem_confirm", "confirm", "fab"),
        contentDescriptions = listOf("Next", "Done", "OK", "Add"),
        texts = listOf("Add", "OK", "Next", "Done")
    )

    /**
     * The "N participants" header on a group info screen. Read before and after
     * an add so success can be *verified* rather than assumed — the same
     * precedent as [WaSender] waiting for the compose box to clear.
     */
    val GroupParticipantCountHeader = Selector(
        name = "group participant count header",
        viewIds = listOf("participants_title", "group_participants_title", "participants_search_title")
    )

    /**
     * The invite-link offer WhatsApp shows when someone's privacy settings block
     * being added. Its presence is the strongest signal that this person belongs
     * in the never-retried invite bucket.
     */
    val InviteViaLinkButton = Selector(
        name = "invite via link button",
        viewIds = listOf("invite_link_btn", "invite_via_link", "invite_button"),
        texts = listOf("Invite to group via link", "Send invite link", "Invite via link")
    )

    /** The button that dismisses a WhatsApp dialog without acting on it. */
    val DialogDismissButton = Selector(
        name = "dialog dismiss button",
        viewIds = listOf("cancel_btn", "ok_btn", "button1", "button2"),
        texts = listOf("OK", "Cancel", "Not now", "Dismiss")
    )

    /**
     * Pulls "12" out of "12 participants".
     *
     * Written with `\d+` rather than a `{1,5}` quantifier on purpose. Android's
     * ICU-backed regex engine is stricter than the JVM's about braces and CI
     * cannot catch the difference (`MEMORY.md`, "things learned while
     * building"); a pattern with no braces in it at all cannot trip over that.
     */
    private val PARTICIPANT_COUNT = Regex("(\\d+)\\s*(participants?|members?)", RegexOption.IGNORE_CASE)

    /**
     * The participant count anywhere in [visibleText], or null when nothing on
     * screen says one. Null means "don't know" and must never be read as zero —
     * an add verified against a guessed count is not verified.
     */
    fun participantCountIn(visibleText: String?): Int? {
        if (visibleText.isNullOrBlank()) return null
        return PARTICIPANT_COUNT.find(visibleText)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Fragments meaning "this person is already in the group". Not an error and
     * not a failure — it must not feed the two-consecutive-failure breaker.
     */
    val ALREADY_MEMBER_TEXT_FRAGMENTS = listOf(
        "already in this group",
        "already a participant",
        "is already in",
        "already added"
    )

    // ---------------------------------------------------------------------
    // Restriction / warning dialogs — these feed the circuit breaker
    // (architecture doc section 6, layer 4). Text-based by necessity: WhatsApp
    // renders these as generic dialogs with no distinguishing view-id, so
    // matching is fuzzy and locale-bound by design, not by oversight.
    // ---------------------------------------------------------------------

    /**
     * Fragments of the messages WhatsApp shows when it is throttling or
     * restricting an account. Matched case-insensitively as substrings against
     * any visible text on screen.
     *
     * A match here must pause the campaign immediately — see
     * [com.tricreta.scopewa.brain.safety.CircuitBreaker] and architecture doc
     * section 6, layer 4. False positives here cost a pause the user can
     * resume; false negatives cost the number. Bias towards over-matching.
     */
    val RESTRICTION_TEXT_FRAGMENTS = listOf(
        "your account has been banned",
        "this account is not allowed",
        "you can't send messages",
        "you cannot send messages",
        "temporarily banned",
        "account restricted",
        "too many messages",
        "try again later",
        "couldn't send",
        "message not sent"
    )

    /**
     * Fragments shown when a number cannot be added to a group because of the
     * recipient's "who can add me to groups" privacy setting. Per architecture
     * doc section 3.2, these people belong in a **"send invite link"** bucket
     * and must never be retried — it is a WhatsApp rule, not a transient error.
     */
    val PRIVACY_BLOCKED_TEXT_FRAGMENTS = listOf(
        "couldn't be added",
        "could not be added",
        "can't be added",
        "invite",
        "privacy settings"
    )

    /**
     * Case-insensitive substring match of [haystack] against a fragment list.
     * Returns the fragment that matched, or null. Returning *which* fragment
     * matched keeps the activity log honest about why a campaign auto-paused.
     */
    fun matchFragment(haystack: String?, fragments: List<String>): String? {
        if (haystack.isNullOrBlank()) return null
        val lower = haystack.lowercase()
        return fragments.firstOrNull { lower.contains(it.lowercase()) }
    }

    /** True when [packageName] is a WhatsApp variant this app knows how to drive. */
    fun isSupportedPackage(packageName: String?): Boolean =
        packageName != null && packageName in SUPPORTED_PACKAGES
}
