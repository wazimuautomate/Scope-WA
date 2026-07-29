package com.tricreta.scopewa.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Selector-capture tool — the thing that keeps the promise in architecture doc
 * section 8 that a WhatsApp UI change is "a small patch + a release".
 *
 * When a selector in [com.tricreta.scopewa.accessibility.WaSelectors] goes
 * stale, fixing it needs the real view-ids off a real handset. Without this
 * screen that means attaching a laptop and running `uiautomatorviewer`; with
 * it, whoever holds the phone captures the screen and shares the text.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Capture a WhatsApp screen", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use this when Scope WA stops recognising part of WhatsApp. It records " +
                        "the names WhatsApp gives its buttons and text boxes, which is what " +
                        "a fix needs.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap start, then switch to WhatsApp and go to the screen that's not " +
                        "working. Scope WA captures whatever is in front when the countdown " +
                        "ends. Nothing leaves the phone unless you share it.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        when (val current = state) {
            CaptureState.Idle -> {
                Button(onClick = { viewModel.startCapture() }) {
                    Text("Start 10-second capture")
                }
            }

            is CaptureState.CountingDown -> {
                Text(
                    "Switch to WhatsApp now — capturing in ${current.secondsLeft}s",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            is CaptureState.Failed -> {
                Text(
                    current.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { viewModel.startCapture() }) { Text("Try again") }
            }

            is CaptureState.Captured -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(current.text)) }
                    ) { Text("Copy") }
                    OutlinedButton(onClick = { shareText(context, current.text) }) {
                        Text("Share")
                    }
                    OutlinedButton(onClick = { viewModel.reset() }) { Text("Clear") }
                }

                Card(Modifier.fillMaxWidth()) {
                    // The dump is wide, fixed-width text; let it scroll sideways
                    // rather than wrapping every line into an unreadable mess.
                    Text(
                        text = current.text,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Scope WA screen capture")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share capture"))
}
