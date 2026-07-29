package com.tricreta.scopewa.ui.contacts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tricreta.scopewa.ui.navigation.ScopeWaDestination

private const val LIST_ID = "listId"

/** No list chosen yet — the import screen just adds to Contacts. */
private const val NO_LIST = -1L

/**
 * Every Contacts route, registered in one place so `ScopeWaNavHost` stays a
 * one-line diff per phase (see `docs/BUILD-PLAN.md`, shared hotspots).
 */
fun NavGraphBuilder.contactsGraph(navController: NavHostController) {
    composable(ScopeWaDestination.Contacts.route) {
        ContactsScreen(
            onOpenList = { navController.navigate(contactListRoute(it)) },
            onImport = { navController.navigate(importRoute(null)) }
        )
    }

    composable(
        route = "${ScopeWaDestination.Contacts.route}/list/{$LIST_ID}",
        arguments = listOf(navArgument(LIST_ID) { type = NavType.LongType })
    ) { entry ->
        ContactListDetailScreen(
            listId = entry.arguments?.getLong(LIST_ID) ?: NO_LIST,
            onBack = { navController.popBackStack() },
            onImportInto = { navController.navigate(importRoute(it)) }
        )
    }

    composable(
        route = "${ScopeWaDestination.Contacts.route}/import?$LIST_ID={$LIST_ID}",
        arguments = listOf(
            navArgument(LIST_ID) {
                type = NavType.LongType
                defaultValue = NO_LIST
            }
        )
    ) { entry ->
        ImportContactsScreen(
            preselectedListId = entry.arguments?.getLong(LIST_ID)?.takeIf { it != NO_LIST },
            onDone = { navController.popBackStack() }
        )
    }
}

private fun contactListRoute(listId: Long) = "${ScopeWaDestination.Contacts.route}/list/$listId"

private fun importRoute(listId: Long?) =
    "${ScopeWaDestination.Contacts.route}/import?$LIST_ID=${listId ?: NO_LIST}"
