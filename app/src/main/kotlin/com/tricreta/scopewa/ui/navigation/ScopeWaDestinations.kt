package com.tricreta.scopewa.ui.navigation

/**
 * One route per screen in ARCHITECTURE-V2-WHATSAPP.md section 7.
 *
 * As of the 1.0.0 integration every one of these resolves to a real screen —
 * the `ComingSoonScreen` placeholders Phases 6 and 7 stood behind are gone. Not
 * all of them are on the bottom bar; see [ScopeWaBottomBar] for which five are
 * and where the rest live.
 */
enum class ScopeWaDestination(val route: String, val label: String) {
    Home("home", "Home"),
    Contacts("contacts", "Contacts"),
    Extract("extract", "Extract"),
    Templates("templates", "Templates"),
    Campaign("campaign", "Campaign"),
    Running("running", "Running"),
    GroupAdd("group_add", "Group Add"),

    /** Phase 7: live progress for one group-add job. */
    GroupAddRunning("group_add_running", "Group Add"),

    ActivityLog("activity_log", "Activity log"),
    Settings("settings", "Settings"),

    /**
     * The overflow half of the navigation — see
     * [com.tricreta.scopewa.ui.more.MoreScreen]. Not a screen from architecture
     * doc section 7; it exists because the app has more top-level destinations
     * than a Material bottom bar can hold.
     */
    More("more", "More"),

    /** Phase 1: guided Accessibility-permission walkthrough. */
    Setup("setup", "Setup"),

    /** Phase 1: WhatsApp screen-capture tool for repairing stale selectors. */
    Diagnostics("diagnostics", "Diagnostics")
}
