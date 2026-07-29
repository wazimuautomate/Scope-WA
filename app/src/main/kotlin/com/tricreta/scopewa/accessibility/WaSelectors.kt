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

    /** Localised admin badge wording, matched as a fallback. Best-effort. */
    val ADMIN_LABEL_TEXTS = listOf("Group admin", "Admin", "Super admin")

    /**
     * The admin badge drawn on a participant row. Phase 4 also falls back to
     * matching [ADMIN_LABEL_TEXTS] anywhere in the row, because on several
     * layouts this badge carries no id of its own.
     */
    val ParticipantAdminBadge = Selector(
        name = "participant admin badge",
        viewIds = listOf("admin_indicator", "group_admin_indicator"),
        texts = ADMIN_LABEL_TEXTS
    )

    /**
     * The row that opens the full participant list — WhatsApp collapses long
     * lists behind "View all" / "See all N". Matched by text because it carries
     * no stable id of its own.
     */
    val ViewAllParticipants = Selector(
        name = "view-all participants row",
        viewIds = listOf("see_all_participants", "participants_search"),
        texts = listOf("View all", "See all")
    )

    /**
     * The group subject on the group-info screen. Used as the extraction's group
     * name and to confirm the right group is open before reading any rows.
     */
    val GroupTitle = Selector(
        name = "group title",
        viewIds = listOf("conversation_contact_name", "group_name", "subject")
    )

    /**
     * The "N participants" / "N members" header, parsed by
     * [parseReportedMemberCount].
     */
    val ParticipantCountHeader = Selector(
        name = "participant count header",
        viewIds = listOf("participants_title", "group_participants_count"),
        texts = listOf("participants", "members")
    )

    /**
     * Pulls the member total out of a header like "824 participants" or
     * "Participants (824)".
     *
     * Worth parsing: comparing this against the number of rows actually read is
     * the only reliable way to notice that scrolling stopped early. A partial
     * extraction that presents itself as complete is the worst outcome in this
     * phase, because the user acts on it.
     */
    fun parseReportedMemberCount(headerText: String?): Int? {
        if (headerText.isNullOrBlank()) return null
        val lower = headerText.lowercase()
        if (!lower.contains("participant") && !lower.contains("member")) return null
        return Regex("""\d[\d,\s]*""").find(headerText)
            ?.value
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
    }

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
