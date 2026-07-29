package com.tricreta.scopewa.accessibility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tricreta.scopewa.brain.whatsapp.WaDeepLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of the "can I see WhatsApp?" test.
 *
 * Every failure case is separate and carries what the user should do about
 * it. A single boolean would make the most important screen in Phase 1
 * useless: "it didn't work" is not something the client can act on, whereas
 * "WhatsApp opened but the compose box wasn't found — WhatsApp's layout has
 * probably changed, send us a screen dump" is.
 */
sealed class ProbeResult {

    /** Everything works: WhatsApp opened and its compose box was located. */
    data class Success(
        val target: WaPackage,
        val whatsAppVersion: String?,
        val composeBoxStrategy: String,
        val elapsedMillis: Long
    ) : ProbeResult()

    /** The chosen WhatsApp variant isn't installed on this phone. */
    data class NotInstalled(val target: WaPackage) : ProbeResult()

    /** The accessibility service isn't bound — the permission step isn't done. */
    data object ServiceNotConnected : ProbeResult()

    /** The deep link couldn't be opened at all. */
    data class CouldNotOpenWhatsApp(val target: WaPackage) : ProbeResult()

    /**
     * WhatsApp never came to the foreground within the timeout. Usually a slow
     * cold start, occasionally an OEM power manager blocking the launch.
     */
    data class WhatsAppNeverAppeared(
        val target: WaPackage,
        val lastSeenPackage: String?,
        val timeoutMillis: Long
    ) : ProbeResult()

    /**
     * WhatsApp was on screen and readable, but no [WaSelectors.ComposeBox]
     * candidate matched. This is the interesting failure: the service works,
     * the selectors are stale. The fix is a [WaScreenDump] and a one-line
     * patch — architecture doc section 8.
     */
    data class ComposeBoxNotFound(
        val target: WaPackage,
        val whatsAppVersion: String?,
        val screenDump: String
    ) : ProbeResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Runs the Phase 1 acceptance test from architecture doc section 9: open
 * WhatsApp via the documented `wa.me` deep link and confirm the accessibility
 * service can actually read its compose box.
 *
 * This is the single most important thing to verify early. Nothing in phases
 * 4, 5 or 7 is possible if this fails, and it can only be proven on a real
 * handset — so the app ships the test rather than assuming the answer.
 *
 * ## Why polling rather than events
 *
 * The service could correlate `TYPE_WINDOW_STATE_CHANGED` events instead, but
 * WhatsApp emits a burst of window and content events during a cold start and
 * picking the right one is fiddly. Polling the active window every
 * [POLL_INTERVAL_MS] is boring, obvious, and easy to reason about. Phase 5
 * will need event handling for restriction dialogs; the probe does not.
 */
object WaProbe {

    private const val POLL_INTERVAL_MS = 250L
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * A number the probe opens a chat with. Deliberately a documentation
     * example number that cannot belong to a real person — the probe must
     * never risk messaging someone. **Nothing is ever sent:** the deep link
     * only opens the compose screen with text prefilled, and the probe reads
     * the screen and stops. It never touches the send button.
     */
    private const val PROBE_NUMBER = "+1555000000"

    suspend fun run(
        context: Context,
        target: WaPackage,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): ProbeResult {
        if (!target.isInstalledOn(context)) return ProbeResult.NotInstalled(target)
        if (!WaServiceBridge.isConnected.value) return ProbeResult.ServiceNotConnected

        val version = target.versionNameOn(context)

        if (!openWhatsApp(context, target)) {
            return ProbeResult.CouldNotOpenWhatsApp(target)
        }

        val startedAt = System.currentTimeMillis()
        var lastSeenPackage: String? = null

        val result: ProbeResult.Success? = withTimeoutOrNull(timeoutMillis) {
            var success: ProbeResult.Success? = null
            while (success == null) {
                val root = WaServiceBridge.currentWindowRoot()
                val packageName = root?.packageName?.toString()
                if (packageName != null) lastSeenPackage = packageName

                if (root != null && packageName == target.packageName) {
                    val match = NodeFinder.find(root, WaSelectors.ComposeBox, target.packageName)
                    if (match != null) {
                        success = ProbeResult.Success(
                            target = target,
                            whatsAppVersion = version,
                            composeBoxStrategy = match.strategy.label,
                            elapsedMillis = System.currentTimeMillis() - startedAt
                        )
                    }
                }
                if (success == null) delay(POLL_INTERVAL_MS)
            }
            success
        }

        if (result != null) return result

        // Timed out. Distinguish "WhatsApp never showed up" from "WhatsApp was
        // right there but we couldn't find the box" — they need opposite fixes.
        val finalRoot = WaServiceBridge.currentWindowRoot()
        val finalPackage = finalRoot?.packageName?.toString()

        return if (finalRoot != null && finalPackage == target.packageName) {
            ProbeResult.ComposeBoxNotFound(
                target = target,
                whatsAppVersion = version,
                screenDump = buildString {
                    appendLine(WaScreenDump.renderSelectorReport(finalRoot, target.packageName))
                    appendLine()
                    appendLine(WaScreenDump.render(finalRoot))
                }
            )
        } else {
            ProbeResult.WhatsAppNeverAppeared(
                target = target,
                lastSeenPackage = lastSeenPackage,
                timeoutMillis = timeoutMillis
            )
        }
    }

    /**
     * Opens a chat via the documented `wa.me` link, targeted explicitly at the
     * chosen package so that having both WhatsApp and WhatsApp Business
     * installed doesn't raise a chooser — the client has both (architecture
     * doc section 10, Q1).
     */
    private fun openWhatsApp(context: Context, target: WaPackage): Boolean {
        val url = WaDeepLink.chatUrl(PROBE_NUMBER, "Scope WA setup test — nothing will be sent.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(target.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
