package com.tricreta.scopewa.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.WaScreenDump
import com.tricreta.scopewa.accessibility.WaSelectors
import com.tricreta.scopewa.accessibility.WaServiceBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CaptureState {
    data object Idle : CaptureState()

    /** Counting down while the user switches to the WhatsApp screen they want captured. */
    data class CountingDown(val secondsLeft: Int) : CaptureState()

    data class Captured(val text: String) : CaptureState()

    data class Failed(val reason: String) : CaptureState()
}

/**
 * Drives the screen-capture tool behind [WaScreenDump].
 *
 * The capture is delayed rather than immediate because of an obvious but
 * easily-missed constraint: while the user is looking at Scope WA, the
 * foreground window *is* Scope WA. To dump a WhatsApp screen the user has to
 * be on that screen, so the tool counts down and grabs whatever is in front
 * when it reaches zero.
 */
class DiagnosticsViewModel : ViewModel() {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun startCapture(countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS) {
        if (_state.value is CaptureState.CountingDown) return

        viewModelScope.launch {
            if (!WaServiceBridge.isConnected.value) {
                _state.value = CaptureState.Failed(
                    "The Accessibility permission isn't active, so Scope WA can't read any " +
                        "screen. Finish setup first."
                )
                return@launch
            }

            for (remaining in countdownSeconds downTo 1) {
                _state.value = CaptureState.CountingDown(remaining)
                delay(1_000)
            }

            val root = WaServiceBridge.currentWindowRoot()
            val packageName = root?.packageName?.toString()

            _state.value = when {
                root == null -> CaptureState.Failed(
                    "Couldn't read the screen. Make sure WhatsApp is open and in front."
                )

                !WaSelectors.isSupportedPackage(packageName) -> CaptureState.Failed(
                    "The screen in front was \"$packageName\", not WhatsApp. Scope WA is only " +
                        "allowed to read WhatsApp and WhatsApp Business — try again and switch " +
                        "to WhatsApp before the countdown ends."
                )

                else -> CaptureState.Captured(
                    buildString {
                        appendLine("WhatsApp variant: ${WaPackage.fromPackageName(packageName)?.displayName ?: packageName}")
                        appendLine()
                        appendLine(WaScreenDump.renderSelectorReport(root, packageName!!))
                        appendLine()
                        appendLine("Full screen tree:")
                        appendLine(WaScreenDump.render(root))
                    }
                )
            }
        }
    }

    fun reset() {
        _state.value = CaptureState.Idle
    }

    private companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 10
    }
}
