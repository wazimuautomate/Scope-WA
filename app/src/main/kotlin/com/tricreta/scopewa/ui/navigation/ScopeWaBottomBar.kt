package com.tricreta.scopewa.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Bottom navigation, modelled on the Tasks / Recipients / Templates / Settings
 * bar in reference screenshot 02.
 *
 * **Deliberately minimal, and Phase 1 should feel free to replace it.** It
 * exists because Phase 2's Contacts screens were otherwise unreachable — the
 * Phase 0 skeleton starts on Home and Home has no links yet. Only four of the
 * nine destinations are here; the rest are reached from inside their own area
 * once their phase lands.
 */
private enum class BottomDestination(
    val destination: ScopeWaDestination,
    val icon: ImageVector,
    /** Shorter than the destination's own label where the bar would otherwise wrap. */
    val label: String = destination.label
) {
    Home(ScopeWaDestination.Home, Icons.Default.Home),
    Contacts(ScopeWaDestination.Contacts, Icons.Default.Person),
    Campaign(ScopeWaDestination.Campaign, Icons.Default.Send),
    Templates(ScopeWaDestination.Templates, Icons.AutoMirrored.Filled.List),
    ActivityLog(ScopeWaDestination.ActivityLog, Icons.Default.Info, "Activity"),
    Settings(ScopeWaDestination.Settings, Icons.Default.Settings)
}

@Composable
fun ScopeWaBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        BottomDestination.entries.forEach { entry ->
            val route = entry.destination.route
            NavigationBarItem(
                // Sub-routes such as `contacts/list/3` should keep Contacts lit.
                selected = currentRoute == route || currentRoute?.startsWith("$route/") == true,
                onClick = {
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(ScopeWaDestination.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(entry.label) }
            )
        }
    }
}
