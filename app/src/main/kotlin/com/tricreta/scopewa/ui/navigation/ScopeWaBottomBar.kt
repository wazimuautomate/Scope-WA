package com.tricreta.scopewa.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
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
 * ## Why there are five entries and not eight
 *
 * Phases 4, 6 and 7 each added a top-level screen, and by 1.0.0 the bar had
 * grown to seven items — past Material's 3–5 for `NavigationBar`, which on a
 * small phone means labels truncate to slivers and the tap targets stop being
 * distinguishable. Meanwhile Phase 7's Group Add had *no* bar entry and nothing
 * else linked to it, so a finished screen was unreachable.
 *
 * The fix is not to drop anything. The four screens the client walks through to
 * run a campaign — Home, Contacts, Templates, Campaign — keep their tabs, and
 * everything else (Extract, Group Add, Activity log, Setup & permissions,
 * Diagnostics) moves one tap away behind **More**, which is
 * [com.tricreta.scopewa.ui.more.MoreScreen]. Every destination is still
 * reachable; see `MEMORY.md`.
 */
private enum class BottomDestination(
    val destination: ScopeWaDestination,
    val icon: ImageVector,
    /** Shorter than the destination's own label where the bar would otherwise wrap. */
    val label: String = destination.label
) {
    Home(ScopeWaDestination.Home, Icons.Default.Home),
    Contacts(ScopeWaDestination.Contacts, Icons.Default.Person),
    Templates(ScopeWaDestination.Templates, Icons.AutoMirrored.Filled.List),
    Campaign(ScopeWaDestination.Campaign, Icons.Default.Send),
    More(ScopeWaDestination.More, Icons.Default.MoreVert)
}

/**
 * The destinations that live behind **More**. Listed so the More tab stays lit
 * while the user is on one of them — a bar where nothing is selected reads as a
 * screen you got to by accident.
 */
private val OVERFLOW_DESTINATIONS = setOf(
    ScopeWaDestination.Extract,
    ScopeWaDestination.GroupAdd,
    ScopeWaDestination.GroupAddRunning,
    ScopeWaDestination.ActivityLog,
    ScopeWaDestination.Settings,
    ScopeWaDestination.Setup,
    ScopeWaDestination.Diagnostics
)

@Composable
fun ScopeWaBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        BottomDestination.entries.forEach { entry ->
            val route = entry.destination.route
            val onOverflowScreen = entry == BottomDestination.More &&
                OVERFLOW_DESTINATIONS.any { currentRoute.isUnder(it.route) }
            NavigationBarItem(
                // Sub-routes such as `contacts/list/3` should keep Contacts lit.
                selected = currentRoute.isUnder(route) || onOverflowScreen,
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

/** True when this route *is* [route] or is one of its sub-routes. */
private fun String?.isUnder(route: String): Boolean =
    this == route || this?.startsWith("$route/") == true
