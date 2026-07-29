package com.tricreta.scopewa.accessibility

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * Everything about getting — and verifying — the Accessibility permission.
 *
 * Architecture doc section 5.1 flags this as needing "a guided walkthrough
 * screen". Two things make that walkthrough genuinely necessary rather than
 * nice-to-have:
 *
 * 1. **The permission is scary and invasive**, correctly so. The user is
 *    handing an app the ability to read every screen. They deserve a plain
 *    explanation of the scoping before they tap through.
 * 2. **Android 13 (API 33) blocks it outright for sideloaded apps.** Scope WA
 *    ships as a direct-install APK, not through the Play Store (section 4), so
 *    it lands in exactly the bucket Android calls a "restricted setting". The
 *    accessibility toggle appears but is greyed out, and the fix — App info →
 *    ⋮ → *Allow restricted settings* — is unguessable. Without this
 *    walkthrough the app looks broken on the client's newer phones.
 */
object AccessibilityPermission {

    /**
     * Whether the user has enabled Scope WA's service in Android Settings.
     *
     * Distinct from [WaServiceBridge.isConnected], which reports whether the
     * service is bound *right now*. Enabled-but-not-connected is a real state
     * (briefly after toggling, and after a force-stop on some OEM builds), and
     * the two answers together are what make a "permissions health" readout
     * trustworthy.
     */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, WaAccessibilityService::class.java)

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // The setting is a ':'-separated list of flattened ComponentNames.
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        for (entry in splitter) {
            val parsed = ComponentName.unflattenFromString(entry) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    /**
     * Opens Android's Accessibility settings.
     *
     * The client runs Samsung and Tecno handsets (architecture doc section 10,
     * Q4), and OEM Settings apps do rearrange or rename this screen. If the
     * standard action is missing we fall back to the top-level Settings app
     * rather than crashing on [ActivityNotFoundException] — the walkthrough
     * text tells the user where to go from there.
     *
     * @return true if some Settings screen was opened.
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, accessibility)) return true

        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, fallback)
    }

    /**
     * Opens this app's "App info" page — where *Allow restricted settings*
     * lives on Android 13+. See the class doc for why that matters here.
     */
    fun openAppInfo(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, intent)
    }

    /**
     * Whether this device is new enough to enforce the restricted-settings
     * block. Used to decide whether the walkthrough shows that step at all —
     * showing it on an Android 12 phone would send the user hunting for a menu
     * item that isn't there.
     */
    fun mayNeedRestrictedSettingsUnlock(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Opens battery-optimisation settings. A campaign is a long-running
     * foreground service; on aggressive OEM power management (Samsung and
     * Tecno both qualify) an unexempted app gets frozen mid-run, which the
     * user experiences as a campaign that silently stopped.
     */
    fun openBatteryOptimisationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, intent)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
