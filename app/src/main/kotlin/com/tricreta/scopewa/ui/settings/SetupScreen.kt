package com.tricreta.scopewa.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopewa.accessibility.AccessibilityPermission
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.ui.theme.ScopeAmber
import com.tricreta.scopewa.ui.theme.ScopeGreen

/**
 * The Phase 1 permission walkthrough (architecture doc section 5.1: "it needs
 * a guided walkthrough screen").
 *
 * Structured as ordered steps rather than a wall of settings because the
 * Accessibility grant genuinely is multi-stage on a sideloaded app, and each
 * stage fails in a way that looks identical from the outside — a greyed-out
 * toggle, a toggle that switches itself back off, a campaign that dies
 * silently ten minutes in. Naming the steps is what makes those
 * distinguishable.
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Every step here is completed by leaving for Android Settings, so cached
    // state is stale by definition on the way back. Re-read on each resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setup", style = MaterialTheme.typography.titleLarge)

        ReadinessBanner(state)

        StepCard(
            number = 1,
            title = "Choose which WhatsApp to drive",
            isDone = state.selectedPackage != null
        ) {
            if (!state.hasWhatsApp) {
                Text(
                    "Neither WhatsApp nor WhatsApp Business is installed. Scope WA sends " +
                        "through the WhatsApp app already on this phone — it can't message " +
                        "anyone by itself.",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    "Campaigns will run through the app you pick here. Use the number you're " +
                        "willing to risk — not your main business line.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                state.installedPackages.forEach { target ->
                    PackageOption(
                        target = target,
                        selected = state.selectedPackage == target,
                        onSelect = { viewModel.selectPackage(target) }
                    )
                }
            }
        }

        StepCard(
            number = 2,
            title = "Turn on the Accessibility permission",
            isDone = state.serviceConnected
        ) {
            Text(
                "This is how Scope WA reads WhatsApp's screen and taps its buttons — the " +
                    "same technique screen readers use. Scope WA is restricted to WhatsApp " +
                    "and WhatsApp Business only; it cannot see any other app.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "In the list that opens, find Scope WA and turn it on.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(
                label = "Enabled in Android Settings",
                isOk = state.serviceEnabledInSettings
            )
            StatusLine(
                label = "Connected and able to read WhatsApp",
                isOk = state.serviceConnected
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { AccessibilityPermission.openAccessibilitySettings(context) }) {
                Text("Open Accessibility settings")
            }
        }

        if (state.mayNeedRestrictedUnlock) {
            StepCard(
                number = 3,
                title = "If the switch is greyed out",
                isDone = state.serviceConnected
            ) {
                Text(
                    "Android blocks the Accessibility permission for apps installed outside " +
                        "the Play Store. Scope WA is installed directly, so this affects it.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Fix it in App info: tap the ⋮ menu in the top corner, choose " +
                        "\"Allow restricted settings\", then go back to step 2.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { AccessibilityPermission.openAppInfo(context) }) {
                    Text("Open App info")
                }
            }
        }

        StepCard(
            number = if (state.mayNeedRestrictedUnlock) 4 else 3,
            title = "Stop Android from pausing campaigns",
            isDone = false,
            isOptional = true
        ) {
            Text(
                "A campaign runs for hours. Android's battery saver will freeze it partway " +
                    "through unless Scope WA is exempted — which looks like a campaign that " +
                    "stopped for no reason.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Find Scope WA in the list and set it to \"Don't optimise\" or \"Unrestricted\".",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { AccessibilityPermission.openBatteryOptimisationSettings(context) }
            ) {
                Text("Open battery settings")
            }
        }

        StepCard(
            number = if (state.mayNeedRestrictedUnlock) 5 else 4,
            title = "Test it",
            isDone = (state.probeState as? ProbeState.Finished)?.presentation?.isSuccess == true
        ) {
            Text(
                "Opens WhatsApp and checks that Scope WA can find the message box. " +
                    "Nothing is sent to anyone.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(12.dp))

            when (val probe = state.probeState) {
                ProbeState.Idle -> {
                    Button(
                        onClick = { viewModel.runProbe(context) },
                        enabled = state.selectedPackage != null
                    ) {
                        Text("Run the test")
                    }
                }

                ProbeState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "  Opening WhatsApp… come back to Scope WA if it doesn't return on its own.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                is ProbeState.Finished -> {
                    ProbeResultBlock(
                        presentation = probe.presentation,
                        onRetry = { viewModel.runProbe(context) },
                        onShare = { shareDiagnostics(context, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessBanner(state: SetupUiState) {
    val (message, color) = when {
        state.isReady -> "Ready to send" to ScopeGreen
        !state.hasWhatsApp -> "WhatsApp isn't installed" to MaterialTheme.colorScheme.error
        else -> "Setup isn't finished — campaigns can't run yet" to ScopeAmber
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
            color = color
        )
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    isDone: Boolean,
    isOptional: Boolean = false,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isDone) "✓" else "$number.",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDone) ScopeGreen else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.fillMaxWidth(0.03f))
                Text(
                    text = if (isOptional) "$title (recommended)" else title,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PackageOption(
    target: WaPackage,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(target.displayName, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusLine(label: String, isOk: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (isOk) "✓" else "✗",
            color = if (isOk) ScopeGreen else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )
        Text("  $label", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProbeResultBlock(
    presentation: com.tricreta.scopewa.accessibility.ProbeResultPresenter.Presentation,
    onRetry: () -> Unit,
    onShare: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = presentation.headline,
            style = MaterialTheme.typography.titleLarge,
            color = if (presentation.isSuccess) ScopeGreen else MaterialTheme.colorScheme.error
        )
        Text(presentation.detail, style = MaterialTheme.typography.bodyLarge)

        presentation.nextStep?.let { nextStep ->
            HorizontalDivider()
            Text("What to do", style = MaterialTheme.typography.labelLarge)
            Text(nextStep, style = MaterialTheme.typography.bodyLarge)
        }

        presentation.shareableDiagnostics?.let { diagnostics ->
            HorizontalDivider()
            Text("Diagnostics", style = MaterialTheme.typography.labelLarge)
            Text(
                text = diagnostics,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) {
                    Text("Copy")
                }
                OutlinedButton(onClick = { onShare(diagnostics) }) {
                    Text("Share")
                }
            }
        }

        Button(onClick = onRetry) { Text("Run the test again") }
    }
}

private fun shareDiagnostics(context: Context, diagnostics: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Scope WA diagnostics")
        putExtra(Intent.EXTRA_TEXT, diagnostics)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
}
