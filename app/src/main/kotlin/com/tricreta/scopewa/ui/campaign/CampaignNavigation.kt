package com.tricreta.scopewa.ui.campaign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tricreta.scopewa.jobrunner.CampaignRunState
import com.tricreta.scopewa.ui.navigation.ScopeWaDestination
import com.tricreta.scopewa.ui.running.CampaignRunningScreen

private const val CAMPAIGN_ID = "campaignId"
private const val NO_CAMPAIGN = -1L

/**
 * Phase 5's routes, registered in one place so `ScopeWaNavHost` stays a
 * one-line diff per phase (see `docs/BUILD-PLAN.md`, shared hotspots).
 */
fun NavGraphBuilder.campaignGraph(navController: NavHostController) {
    composable(ScopeWaDestination.Campaign.route) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // A campaign runs for hours in a foreground service, so the user
            // will leave this screen and come back. Without a way back in, the
            // only evidence a campaign is running would be the notification.
            ResumeRunningBanner(
                onOpen = { navController.navigate(runningRoute(it)) }
            )
            CampaignComposerScreen(
                onBack = { navController.popBackStack() },
                onStarted = { campaignId ->
                    navController.navigate(runningRoute(campaignId)) {
                        // Coming "back" from a running campaign should not
                        // land on a half-filled composer for the campaign that
                        // was just started.
                        popUpTo(ScopeWaDestination.Campaign.route) { inclusive = true }
                    }
                }
            )
        }
    }

    composable(
        route = "${ScopeWaDestination.Running.route}/{$CAMPAIGN_ID}",
        arguments = listOf(navArgument(CAMPAIGN_ID) { type = NavType.LongType })
    ) { entry ->
        CampaignRunningScreen(
            campaignId = entry.arguments?.getLong(CAMPAIGN_ID) ?: NO_CAMPAIGN,
            onBack = { navController.popBackStack() }
        )
    }
}

@Composable
private fun ResumeRunningBanner(onOpen: (Long) -> Unit) {
    val snapshot by CampaignRunState.snapshot.collectAsState()
    val running = snapshot ?: return

    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("A campaign is running", style = MaterialTheme.typography.titleSmall)
            Text(
                text = running.currentRecipient?.let { "Currently: $it" }
                    ?: "Waiting between messages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onOpen(running.campaignId) }) { Text("Open it") }
        }
    }
}

private fun runningRoute(campaignId: Long) = "${ScopeWaDestination.Running.route}/$campaignId"
