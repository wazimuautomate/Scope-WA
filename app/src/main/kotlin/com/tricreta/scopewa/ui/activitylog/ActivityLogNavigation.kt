package com.tricreta.scopewa.ui.activitylog

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tricreta.scopewa.ui.navigation.ScopeWaDestination

private const val CAMPAIGN_ID = "campaignId"
private const val NO_CAMPAIGN = -1L

/**
 * Phase 6's routes, registered in one place so `ScopeWaNavHost` stays a
 * one-line diff per phase (see `docs/BUILD-PLAN.md`, shared hotspots).
 */
fun NavGraphBuilder.activityLogGraph(navController: NavHostController) {
    composable(ScopeWaDestination.ActivityLog.route) {
        ActivityLogScreen(
            onOpenReport = { navController.navigate(campaignReportRoute(it)) }
        )
    }

    composable(
        route = "${ScopeWaDestination.ActivityLog.route}/report/{$CAMPAIGN_ID}",
        arguments = listOf(navArgument(CAMPAIGN_ID) { type = NavType.LongType })
    ) { entry ->
        CampaignReportScreen(
            campaignId = entry.arguments?.getLong(CAMPAIGN_ID) ?: NO_CAMPAIGN,
            onBack = { navController.popBackStack() }
        )
    }
}

/** Reachable from the Running screen once a campaign finishes, too. */
fun campaignReportRoute(campaignId: Long) =
    "${ScopeWaDestination.ActivityLog.route}/report/$campaignId"
