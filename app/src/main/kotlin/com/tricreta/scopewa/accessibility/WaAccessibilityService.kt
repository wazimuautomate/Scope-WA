package com.tricreta.scopewa.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The "Hands" layer — architecture doc section 5.2. Reads WhatsApp's screen
 * and (from Phase 5) taps its buttons on the job runner's behalf.
 *
 * Scoped to `com.whatsapp` and `com.whatsapp.w4b` by
 * `res/xml/accessibility_service_config.xml`; the system will not deliver
 * this service events from any other app. That scoping is a deliberate
 * privacy property, not an optimisation — this permission can read every
 * screen on the phone, and the config is what promises it doesn't.
 *
 * ## What this class deliberately does not do
 *
 * No campaign logic lives here. The service is a dumb pair of hands: it
 * exposes the current window and performs discrete actions. Deciding *whether*
 * to send, *when*, and *to whom* belongs to `brain/` (testable in CI) driven
 * by `jobrunner/`. Keeping that split is what lets the anti-ban rules be
 * verified without a phone — see `CLAUDE.md`.
 */
class WaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        WaServiceBridge.attach(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1 reads the screen on demand (see WaProbe) rather than
        // reacting to the event stream, because a probe knows exactly when it
        // is interested and polling avoids correlating noisy
        // TYPE_WINDOW_CONTENT_CHANGED bursts against an in-flight step.
        //
        // Phase 5 will need real event handling to detect restriction dialogs
        // the moment they appear rather than at the next poll.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        WaServiceBridge.detach()
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        WaServiceBridge.detach()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WaAccessibility"
    }
}
