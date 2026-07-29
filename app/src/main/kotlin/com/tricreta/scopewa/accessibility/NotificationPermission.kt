package com.tricreta.scopewa.accessibility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Everything about getting — and verifying — notification access, the second
 * scary permission this app asks for. Mirrors [AccessibilityPermission]
 * deliberately: same shape, same fallbacks, same honesty in the walkthrough.
 *
 * ## Why the app wants it
 *
 * Architecture doc section 6 layer 3 promises that "anyone who replies
 * STOP/ACHA/SITAKI is permanently excluded — automatically". Until Phase 5's
 * follow-up nothing in the app *observed* replies at all, so "automatic" meant
 * automatic in everything except the noticing. A
 * [com.tricreta.scopewa.accessibility.WaNotificationListener] is the only way
 * to see a reply arrive while WhatsApp is in the background, which is the
 * normal case: the phone is meant to be left alone running a campaign.
 *
 * ## What the user is actually granting
 *
 * Notification access is phone-wide — Android has no per-app scoping for it,
 * unlike the Accessibility service which is restricted to WhatsApp by its
 * config XML. The app compensates in code rather than in the prompt:
 * `ReplyNotificationParser` discards everything that is not from
 * `com.whatsapp` / `com.whatsapp.w4b` before anything else happens, and no
 * message body is ever logged, stored or exported. The walkthrough says this
 * in plain words instead of hiding it.
 *
 * ## It is optional, and the app must keep working without it
 *
 * Campaigns run fine ungranted; opt-outs then have to be marked by hand, and
 * the `ColdBatchNoReplies` circuit breaker stays disabled because a reply count
 * of zero would otherwise mean "nobody is answering" when it really means
 * "nobody is listening".
 */
object NotificationPermission {

    /**
     * Whether the user has ticked Scope WA in Settings → Notification access.
     *
     * Reads the same secure setting Android itself does. Unlike the
     * Accessibility service there is no separate "connected" state worth
     * reporting: the system binds the listener on grant and rebinds it after a
     * reboot without the app doing anything.
     */
    fun isGranted(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }.getOrDefault(false)

    /**
     * Opens Settings → Notification access.
     *
     * Same OEM caveat as [AccessibilityPermission.openAccessibilitySettings]:
     * the client runs Samsung and Tecno handsets and this screen moves around,
     * so a missing action falls back to top-level Settings rather than
     * throwing [ActivityNotFoundException].
     *
     * @return true if some Settings screen was opened.
     */
    fun openNotificationAccessSettings(context: Context): Boolean {
        val listenerSettings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, listenerSettings)) return true

        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, fallback)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
