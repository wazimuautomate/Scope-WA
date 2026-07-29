package com.tricreta.scopewa.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tricreta.scopewa.ui.navigation.ScopeWaDestination

/**
 * The overflow half of the navigation, reached from the bottom bar's **More**
 * tab.
 *
 * Material's `NavigationBar` takes 3–5 destinations; by 1.0.0 the app had eight
 * top-level screens and the bar had quietly grown to seven items, which on a
 * small phone renders as unreadable slivers — and Group Add had no entry at
 * all, so a finished screen was unreachable. Rather than drop a tab, the four
 * screens a campaign actually runs through stay on the bar and everything else
 * lives here.
 *
 * Cards rather than a list, to match `ui/home/HomeScreen.kt`: a heading in the
 * accent-free surface colour plus one line saying what the screen is for, so
 * this reads as a menu of tools rather than a settings index.
 */
@Composable
fun MoreScreen(
    modifier: Modifier = Modifier,
    onOpen: (ScopeWaDestination) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("More", style = MaterialTheme.typography.titleLarge)

        MoreEntry(
            title = "Extract group contacts",
            detail = "Read a WhatsApp group's participants and export them as a file. " +
                "Nothing is ever written to the phone's contacts.",
            onClick = { onOpen(ScopeWaDestination.Extract) }
        )

        MoreEntry(
            title = "Add people to a group",
            detail = "Slowly, and only people who have messaged you or already share " +
                "a group with you. Strictest pacing in the app.",
            onClick = { onOpen(ScopeWaDestination.GroupAdd) }
        )

        MoreEntry(
            title = "Activity log",
            detail = "Every message with an outcome, plus a report per campaign. " +
                "This is the screen that answers \"did Mary get it?\".",
            onClick = { onOpen(ScopeWaDestination.ActivityLog) }
        )

        MoreEntry(
            title = "Setup & permissions",
            detail = "Which WhatsApp to drive, Accessibility, notification access, " +
                "and a test that says whether automation actually works.",
            onClick = { onOpen(ScopeWaDestination.Settings) }
        )

        MoreEntry(
            title = "Diagnostics",
            detail = "Capture a WhatsApp screen dump when a selector goes stale after " +
                "a WhatsApp update.",
            onClick = { onOpen(ScopeWaDestination.Diagnostics) }
        )
    }
}

@Composable
private fun MoreEntry(title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
