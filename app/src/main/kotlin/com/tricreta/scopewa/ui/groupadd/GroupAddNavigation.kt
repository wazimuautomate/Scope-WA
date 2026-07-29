package com.tricreta.scopewa.ui.groupadd

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
import com.tricreta.scopewa.jobrunner.GroupAddRunState
import com.tricreta.scopewa.ui.navigation.ScopeWaDestination

private const val JOB_ID = "groupAddJobId"
private const val NO_JOB = -1L

/** Route for the live screen of one group-add job. */
fun groupAddRunningRoute(jobId: Long) = "${ScopeWaDestination.GroupAddRunning.route}/$jobId"

/**
 * Phase 7's routes, registered in one place so `ScopeWaNavHost` stays a
 * one-line diff per phase (see `docs/BUILD-PLAN.md`, shared hotspots).
 */
fun NavGraphBuilder.groupAddGraph(navController: NavHostController) {
    composable(ScopeWaDestination.GroupAdd.route) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // A group add runs for hours in a foreground service, so the user
            // will leave and come back. Without this, the only evidence it is
            // running would be the notification.
            ResumeRunningBanner(onOpen = { navController.navigate(groupAddRunningRoute(it)) })
            GroupAddSetupScreen(
                onBack = { navController.popBackStack() },
                onStarted = { jobId ->
                    navController.navigate(groupAddRunningRoute(jobId)) {
                        popUpTo(ScopeWaDestination.GroupAdd.route) { inclusive = true }
                    }
                }
            )
        }
    }

    composable(
        route = "${ScopeWaDestination.GroupAddRunning.route}/{$JOB_ID}",
        arguments = listOf(navArgument(JOB_ID) { type = NavType.LongType })
    ) { entry ->
        GroupAddRunningScreen(
            jobId = entry.arguments?.getLong(JOB_ID) ?: NO_JOB,
            onBack = { navController.popBackStack() }
        )
    }
}

@Composable
private fun ResumeRunningBanner(onOpen: (Long) -> Unit) {
    val snapshot by GroupAddRunState.snapshot.collectAsState()
    val running = snapshot ?: return

    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("A group add is running", style = MaterialTheme.typography.titleSmall)
            Text(
                text = running.currentPerson?.let { "Currently: $it" }
                    ?: "Waiting between adds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onOpen(running.jobId) }) { Text("Open it") }
        }
    }
}
