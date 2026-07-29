package com.tricreta.scopewa.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tricreta.scopewa.accessibility.AccessibilityPermission
import com.tricreta.scopewa.accessibility.ProbeResultPresenter
import com.tricreta.scopewa.accessibility.WaPackage
import com.tricreta.scopewa.accessibility.WaProbe
import com.tricreta.scopewa.accessibility.WaServiceBridge
import com.tricreta.scopewa.accessibility.installedWaPackages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the "can I see WhatsApp?" test currently stands. */
sealed class ProbeState {
    data object Idle : ProbeState()
    data object Running : ProbeState()
    data class Finished(val presentation: ProbeResultPresenter.Presentation) : ProbeState()
}

data class SetupUiState(
    val installedPackages: List<WaPackage> = emptyList(),
    val selectedPackage: WaPackage? = null,
    val serviceEnabledInSettings: Boolean = false,
    val serviceConnected: Boolean = false,
    val mayNeedRestrictedUnlock: Boolean = false,
    val probeState: ProbeState = ProbeState.Idle
) {
    val hasWhatsApp: Boolean get() = installedPackages.isNotEmpty()

    /**
     * Whether setup is far enough along to attempt a campaign. Deliberately
     * requires the service to be *connected*, not merely enabled in Settings —
     * a ticked checkbox that hasn't bound yet cannot send anything.
     */
    val isReady: Boolean get() = hasWhatsApp && serviceConnected
}

/**
 * Drives the Phase 1 setup walkthrough (architecture doc section 9, Phase 1).
 *
 * Holds no [Context]: permission state is re-read on demand with whatever
 * context the caller has. The values here go stale the moment the user leaves
 * for Android Settings, so the screen re-reads them on every resume rather
 * than trusting cached state.
 */
class SetupViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            WaServiceBridge.isConnected.collect { connected ->
                _uiState.update { it.copy(serviceConnected = connected) }
            }
        }
    }

    /** Re-reads everything that can change while the user is away in Settings. */
    fun refresh(context: Context) {
        val installed = installedWaPackages(context)
        _uiState.update { current ->
            current.copy(
                installedPackages = installed,
                // Keep an explicit choice; otherwise default to the first
                // installed variant so the common single-app case needs no tap.
                selectedPackage = current.selectedPackage?.takeIf { it in installed }
                    ?: installed.firstOrNull(),
                serviceEnabledInSettings = AccessibilityPermission.isServiceEnabledInSettings(context),
                serviceConnected = WaServiceBridge.isConnected.value,
                mayNeedRestrictedUnlock = AccessibilityPermission.mayNeedRestrictedSettingsUnlock()
            )
        }
    }

    fun selectPackage(target: WaPackage) {
        _uiState.update { it.copy(selectedPackage = target, probeState = ProbeState.Idle) }
    }

    fun runProbe(context: Context) {
        val target = _uiState.value.selectedPackage ?: return
        if (_uiState.value.probeState is ProbeState.Running) return

        _uiState.update { it.copy(probeState = ProbeState.Running) }
        viewModelScope.launch {
            val result = WaProbe.run(context, target)
            _uiState.update {
                it.copy(probeState = ProbeState.Finished(ProbeResultPresenter.present(result)))
            }
        }
    }

    fun dismissProbeResult() {
        _uiState.update { it.copy(probeState = ProbeState.Idle) }
    }
}
