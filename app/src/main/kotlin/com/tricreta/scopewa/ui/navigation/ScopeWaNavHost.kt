package com.tricreta.scopewa.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tricreta.scopewa.data.db.entity.TemplateEntity
import com.tricreta.scopewa.ui.activitylog.activityLogGraph
import com.tricreta.scopewa.ui.common.ComingSoonScreen
import com.tricreta.scopewa.ui.campaign.campaignGraph
import com.tricreta.scopewa.ui.contacts.contactsGraph
import com.tricreta.scopewa.ui.home.HomeScreen
import com.tricreta.scopewa.ui.settings.DiagnosticsScreen
import com.tricreta.scopewa.ui.settings.SetupScreen
import com.tricreta.scopewa.ui.templates.TemplateEditorScreen
import com.tricreta.scopewa.ui.templates.TemplateListScreen
import com.tricreta.scopewa.ui.templates.TemplateRoutes

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

        composable(ScopeWaDestination.Extract.route) {
            ComingSoonScreen("Extract", "Lands in Phase 4 — see architecture doc section 9.")
        }
        composable(ScopeWaDestination.Templates.route) {
            TemplateListScreen(
                onOpenTemplate = { templateId ->
                    navController.navigate(TemplateRoutes.editor(templateId))
                }
            )
        }
        composable(
            route = TemplateRoutes.EDITOR,
            arguments = listOf(
                navArgument(TemplateRoutes.ARG_TEMPLATE_ID) { type = NavType.LongType }
            )
        ) { entry ->
            TemplateEditorScreen(
                templateId = entry.arguments?.getLong(TemplateRoutes.ARG_TEMPLATE_ID)
                    ?: TemplateEntity.NEW_TEMPLATE_ID,
                onDone = { navController.popBackStack() }
            )
        }
        // Phase 5 — the composer and the live progress screen.
        campaignGraph(navController)

        composable(ScopeWaDestination.GroupAdd.route) {
            ComingSoonScreen("Group Add", "Lands in Phase 7 — ships last, strictest settings.")
        }
        // Phase 6 — the log of everything sent, and the per-campaign reports.
        activityLogGraph(navController)
        // Settings currently *is* the setup walkthrough — it covers the
        // WhatsApp-variant choice and permissions health from architecture doc
        // section 7. Pacing profiles, active hours, caps, and warm-up state
        // join it in Phase 5, when there is a campaign for them to govern.
        composable(ScopeWaDestination.Settings.route) { SetupScreen() }
    }
}
