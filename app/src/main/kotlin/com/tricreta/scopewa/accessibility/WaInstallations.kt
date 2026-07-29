package com.tricreta.scopewa.accessibility

import android.content.Context
import android.content.pm.PackageManager

/**
 * The Android-dependent half of [WaPackage]: what is actually installed on
 * this phone.
 *
 * Split out so [WaPackage] itself stays a plain enum — see its class doc.
 *
 * All of these depend on the `<queries>` block in `AndroidManifest.xml`.
 * Without it, Android 11+ package visibility rules hide WhatsApp from
 * [PackageManager] entirely and every check here silently returns "not
 * installed".
 */

fun WaPackage.isInstalledOn(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/**
 * Installed version, for diagnostics. Worth capturing alongside any selector
 * problem report: "compose box not found" is only actionable if we know which
 * WhatsApp build it was not found on — architecture doc section 8 expects UI
 * changes a few times a year.
 */
fun WaPackage.versionNameOn(context: Context): String? = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(packageName, 0).versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/** The WhatsApp variants actually installed, in [WaPackage.entries] order. */
fun installedWaPackages(context: Context): List<WaPackage> =
    WaPackage.entries.filter { it.isInstalledOn(context) }
