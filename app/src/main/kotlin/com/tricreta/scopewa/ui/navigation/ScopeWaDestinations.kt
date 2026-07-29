package com.tricreta.scopewa.ui.navigation

/**
 * One route per screen in ARCHITECTURE-V2-WHATSAPP.md section 7.
 * Screens land in build-order phases (see the doc, section 9); until then each
 * route resolves to a [com.tricreta.scopewa.ui.common.ComingSoonScreen].
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

    /** Phase 1: guided Accessibility-permission walkthrough. */
    Setup("setup", "Setup"),

    /** Phase 1: WhatsApp screen-capture tool for repairing stale selectors. */
    Diagnostics("diagnostics", "Diagnostics")
}
