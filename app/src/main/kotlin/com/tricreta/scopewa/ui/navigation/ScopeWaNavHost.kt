package com.tricreta.scopewa.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tricreta.scopewa.ui.common.ComingSoonScreen
import com.tricreta.scopewa.ui.contacts.contactsGraph
import com.tricreta.scopewa.ui.extract.ExtractScreen
import com.tricreta.scopewa.ui.home.HomeScreen
import com.tricreta.scopewa.ui.settings.DiagnosticsScreen
import com.tricreta.scopewa.ui.settings.SetupScreen

@Composable
fun ScopeWaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = ScopeWaDestination.Home.route,
        modifier = modifier
    ) {
        composable(ScopeWaDestination.Home.route) {
            HomeScreen(
                onOpenSetup = { navController.navigate(ScopeWaDestination.Setup.route) },
                onOpenDiagnostics = { navController.navigate(ScopeWaDestination.Diagnostics.route) }
            )
        }

        composable(ScopeWaDestination.Setup.route) { SetupScreen() }
        composable(ScopeWaDestination.Diagnostics.route) { DiagnosticsScreen() }

        // Phase 2 — lists, import, picker and export all live under `contacts/`.
        contactsGraph(navController)

        // Phase 4 — reads a group's participants through the accessibility service.
        composable(ScopeWaDestination.Extract.route) { ExtractScreen() }
        composable(ScopeWaDestination.Templates.route) {
            ComingSoonScreen("Templates", "Lands in Phase 3 — see architecture doc section 9.")
        }
        composable(ScopeWaDestination.Campaign.route) {
            ComingSoonScreen("Campaign", "Lands in Phase 5 — see architecture doc section 9.")
        }
        composable(ScopeWaDestination.Running.route) {
            ComingSoonScreen("Running", "Lands in Phase 5 — see architecture doc section 9.")
        }
        composable(ScopeWaDestination.GroupAdd.route) {
            ComingSoonScreen("Group Add", "Lands in Phase 7 — ships last, strictest settings.")
        }
        composable(ScopeWaDestination.ActivityLog.route) {
            ComingSoonScreen("Activity log", "Lands in Phase 6 — see architecture doc section 9.")
        }
        // Settings currently *is* the setup walkthrough — it covers the
        // WhatsApp-variant choice and permissions health from architecture doc
        // section 7. Pacing profiles, active hours, caps, and warm-up state
        // join it in Phase 5, when there is a campaign for them to govern.
        composable(ScopeWaDestination.Settings.route) { SetupScreen() }
    }
}
