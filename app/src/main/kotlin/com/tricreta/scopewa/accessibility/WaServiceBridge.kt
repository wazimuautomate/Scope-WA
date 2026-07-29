package com.tricreta.scopewa.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only way the rest of the app reaches [WaAccessibilityService].
 *
 * Accessibility services are instantiated by the system, not by us, so there
 * is no constructor to inject into and no binder worth exposing. A process-
 * scoped singleton that the service attaches itself to on connect is the
 * conventional answer. Everything else (UI, job runner) observes
 * [isConnected] and asks for the current window through here.
 *
 * The service reference is held **weakly in effect** by clearing it in
 * [detach]; the service outlives any individual screen, and holding it after
 * unbind would hand callers a dead node source.
 */
object WaServiceBridge {

    private val _isConnected = MutableStateFlow(false)

    /**
     * Whether the accessibility service is currently bound and able to read
     * screens. Distinct from "the user has ticked it in Android Settings" —
     * see [AccessibilityPermission.isServiceEnabledInSettings]. Both matter:
     * enabled-but-not-bound happens briefly after toggling, and on some OEM
     * builds after the app is force-stopped.
     */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    private var service: WaAccessibilityService? = null

    internal fun attach(instance: WaAccessibilityService) {
        service = instance
        _isConnected.value = true
    }

    internal fun detach() {
        service = null
        _isConnected.value = false
    }

    /**
     * The root node of whatever window is in front right now, or null if the
     * service is not connected or the system declined to hand one over.
     *
     * Callers must not assume this belongs to WhatsApp — check
     * [AccessibilityNodeInfo.getPackageName] against [WaSelectors.isSupportedPackage]
     * before acting on it. Acting on the wrong app's window is exactly the
     * failure mode that makes accessibility automation dangerous.
     */
    fun currentWindowRoot(): AccessibilityNodeInfo? = service?.rootInActiveWindow

    /** The foreground app's package name, or null when nothing is readable. */
    fun currentWindowPackage(): String? = currentWindowRoot()?.packageName?.toString()
}
