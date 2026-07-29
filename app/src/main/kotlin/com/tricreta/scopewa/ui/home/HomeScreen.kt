package com.tricreta.scopewa.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.WaServiceBridge
import com.tricreta.scopewa.accessibility.installedWaPackages
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeGreen

/**
 * Home — architecture doc section 7.
 *
 * Phase 1 delivers the readiness half: whether the app can actually drive
 * WhatsApp right now. Today's counters, warm-up day, and the active-campaign
 * summary need Phases 2 and 5 to exist before they can show anything real, so
 * they are deliberately absent rather than faked with zeroes.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSetup: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val serviceConnected by WaServiceBridge.isConnected.collectAsState()

    var installedPackages by remember { mutableStateOf(emptyList<WaPackage>()) }

    // WhatsApp can be installed or removed, and the permission revoked, while
    // this screen is backgrounded — re-check on resume rather than at first
    // composition only.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installedPackages = installedWaPackages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isReady = serviceConnected && installedPackages.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Scope WA", style = MaterialTheme.typography.titleLarge)

        ReadinessCard(
            isReady = isReady,
            serviceConnected = serviceConnected,
            installedPackages = installedPackages
        )

        if (!isReady) {
            Button(onClick = onOpenSetup) { Text("Finish setup") }
        } else {
            OutlinedButton(onClick = onOpenSetup) { Text("Setup & permissions") }
        }

        OutlinedButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Contacts, templates, and campaigns arrive in later phases — " +
                "see docs/BUILD-PLAN.md.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ReadinessCard(
    isReady: Boolean,
    serviceConnected: Boolean,
    installedPackages: List<WaPackage>
) {
    val headline: String
    val detail: String
    val color = if (isReady) ScopeGreen else ScopeAmber

    when {
        isReady -> {
            headline = "Ready to send"
            detail = "Connected to ${installedPackages.joinToString(" and ") { it.displayName }}."
        }

        installedPackages.isEmpty() -> {
            headline = "WhatsApp not found"
            detail = "Scope WA sends through the WhatsApp app on this phone. " +
                "Install WhatsApp or WhatsApp Business first."
        }

        !serviceConnected -> {
            headline = "Setup not finished"
            detail = "The Accessibility permission isn't active yet, so campaigns can't run."
        }

        else -> {
            headline = "Setup not finished"
            detail = "Some setup steps are still outstanding."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, color = color)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
