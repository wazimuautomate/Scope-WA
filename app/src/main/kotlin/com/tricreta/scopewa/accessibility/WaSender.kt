package com.tricreta.scopewa.accessibility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.tricreta.scopewa.brain.campaign.TypingDelay
import com.tricreta.scopewa.brain.whatsapp.WaDeepLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What happened to one attempted message. */
sealed interface SendOutcome {

    data class Sent(val elapsedMillis: Long) : SendOutcome

    data class NotInstalled(val target: WaPackage) : SendOutcome

    data object ServiceNotConnected : SendOutcome

    data class CouldNotOpenWhatsApp(val target: WaPackage) : SendOutcome

    data class WhatsAppNeverAppeared(val target: WaPackage, val lastSeenPackage: String?) : SendOutcome

    data class ComposeBoxNotFound(val target: WaPackage) : SendOutcome

    data class SendButtonNotFound(val target: WaPackage) : SendOutcome

    /**
     * WhatsApp itself said no — a ban, a restriction, or "couldn't send".
     * This is the one outcome that must stop the whole campaign rather than
     * count as one more failure; see architecture doc section 6 layer 4.
     */
    data class Restricted(val matchedText: String) : SendOutcome

    /** The click landed but the compose box never cleared. */
    data class NotDelivered(val reason: String) : SendOutcome

    /**
     * Deliberately not sent — opted out, suppressed, or inside the per-person
     * cooldown. **Never a failure:** skipping is the anti-ban system working,
     * and counting it toward the consecutive-failure breaker would pause
     * campaigns for doing the right thing.
     */
    data class Skipped(val reason: String) : SendOutcome

    val isSent: Boolean get() = this is Sent

    /** Counts toward the consecutive-failure circuit breaker. */
    val isFailure: Boolean get() = this !is Sent && this !is Skipped

    /** A human-readable reason for `campaign_messages.error`. */
    fun describe(): String = when (this) {
        is Sent -> "Sent"
        is NotInstalled -> "${target.displayName} is not installed on this phone"
        ServiceNotConnected -> "Accessibility service isn't connected — finish setup"
        is CouldNotOpenWhatsApp -> "Couldn't open ${target.displayName}"
        is WhatsAppNeverAppeared ->
            "${target.displayName} never came to the front" +
                (lastSeenPackage?.let { " (saw $it instead)" } ?: "")
        is ComposeBoxNotFound ->
            "Couldn't find the message box — ${target.displayName} may have changed its layout"
        is SendButtonNotFound ->
            "Couldn't find the send button — ${target.displayName} may have changed its layout"
        is Restricted -> "WhatsApp warning: \"$matchedText\""
        is NotDelivered -> reason
        is Skipped -> reason
    }
}

/**
 * Sends one WhatsApp message by driving the real app — the "HANDS" layer from
 * architecture doc section 5.2, and the routine sketched in section 5.1:
 *
 * > open `https://wa.me/<intl>?text=<urlencoded>` via an Intent targeted at the
 * > WhatsApp package, wait for the send button node, click it, verify the
 * > compose box cleared, then hand back to the scheduler.
 *
 * Three things here are load-bearing rather than incidental:
 *
 * 1. **The deep link prefills the text, so nothing is typed.** That avoids
 *    `ACTION_SET_TEXT` on a view WhatsApp owns, which is both fragile and the
 *    kind of thing that breaks on every layout change.
 * 2. **A typing pause is inserted anyway.** The prefill is instant, and a
 *    300-character message appearing and sending in 200ms is exactly the
 *    fingerprint section 6 layer 2 is trying to avoid. [TypingDelay] scales it
 *    with length.
 * 3. **Delivery is verified, not assumed.** The compose box clearing is the
 *    only evidence available that WhatsApp accepted the message; without that
 *    check a campaign against a restricted account would report a clean 100%.
 *
 * Every WhatsApp-specific string lives in [WaSelectors] — never inline one here
 * (`CLAUDE.md`, and architecture doc section 8 for why).
 */
object WaSender {

    const val DEFAULT_APPEAR_TIMEOUT_MS = 20_000L
    const val DEFAULT_DELIVERY_TIMEOUT_MS = 8_000L

    private const val POLL_INTERVAL_MS = 250L
    private const val TAG = "WaSender"

    /**
     * @param e164 recipient in `+254…` form
     * @param text the fully rendered message — this is what actually goes out
     * @param typingDelayMillis overridable so tests and dry runs don't wait
     */
    suspend fun send(
        context: Context,
        target: WaPackage,
        e164: String,
        text: String,
        appearTimeoutMillis: Long = DEFAULT_APPEAR_TIMEOUT_MS,
        deliveryTimeoutMillis: Long = DEFAULT_DELIVERY_TIMEOUT_MS,
        typingDelayMillis: Long = TypingDelay.forText(text)
    ): SendOutcome {
        val startedAt = System.currentTimeMillis()

        if (!target.isInstalledOn(context)) return SendOutcome.NotInstalled(target)
        if (!WaServiceBridge.isConnected.value) return SendOutcome.ServiceNotConnected

        if (!openChat(context, target, e164, text)) {
            return SendOutcome.CouldNotOpenWhatsApp(target)
        }

        // 1. Wait for WhatsApp to come to the front with the chat open.
        var lastSeenPackage: String? = null
        var restriction: String? = null

        val composeAppeared = withTimeoutOrNull(appearTimeoutMillis) {
            var settled = false
            while (!settled) {
                val root = WaServiceBridge.currentWindowRoot()
                val packageName = root?.packageName?.toString()
                if (packageName != null) lastSeenPackage = packageName

                if (root != null && packageName == target.packageName) {
                    val warning = restrictionIn(root)
                    if (warning != null) {
                        restriction = warning
                        settled = true
                    } else if (NodeFinder.find(root, WaSelectors.ComposeBox, target.packageName) != null) {
                        settled = true
                    }
                }
                if (!settled) delay(POLL_INTERVAL_MS)
            }
            true
        } ?: false

        restriction?.let { return SendOutcome.Restricted(it) }

        if (!composeAppeared) {
            val root = WaServiceBridge.currentWindowRoot()
            return if (root?.packageName?.toString() == target.packageName) {
                SendOutcome.ComposeBoxNotFound(target)
            } else {
                SendOutcome.WhatsAppNeverAppeared(target, lastSeenPackage)
            }
        }

        // 2. Look like someone who just typed this, not something that pasted it.
        delay(typingDelayMillis)

        // 3. Click send.
        val root = WaServiceBridge.currentWindowRoot()
        restrictionIn(root)?.let { return SendOutcome.Restricted(it) }

        val sendMatch = NodeFinder.find(root, WaSelectors.SendButton, target.packageName)
            ?: return SendOutcome.SendButtonNotFound(target)
        val clickable = NodeFinder.clickableSelfOrAncestor(sendMatch.node)
            ?: return SendOutcome.SendButtonNotFound(target)

        val clicked = runCatching {
            clickable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
        }.getOrElse {
            Log.w(TAG, "send click threw", it)
            false
        }
        if (!clicked) return SendOutcome.NotDelivered("WhatsApp refused the send tap")

        // 4. Verify. An empty compose box is the only evidence we get.
        var afterSendWarning: String? = null
        val cleared = withTimeoutOrNull(deliveryTimeoutMillis) {
            var settled = false
            while (!settled) {
                val current = WaServiceBridge.currentWindowRoot()
                val warning = restrictionIn(current)
                if (warning != null) {
                    afterSendWarning = warning
                    settled = true
                } else if (composeBoxIsEmpty(current, target)) {
                    settled = true
                }
                if (!settled) delay(POLL_INTERVAL_MS)
            }
            true
        } ?: false

        afterSendWarning?.let { return SendOutcome.Restricted(it) }

        return if (cleared) {
            SendOutcome.Sent(System.currentTimeMillis() - startedAt)
        } else {
            SendOutcome.NotDelivered("The message box didn't clear — treating it as not sent")
        }
    }

    /**
     * True when the compose box is present and empty. A *missing* compose box
     * counts as not-empty on purpose: we can't see it, so we can't claim the
     * message went. Optimism here would silently inflate the success rate.
     */
    private fun composeBoxIsEmpty(root: AccessibilityNodeInfo?, target: WaPackage): Boolean {
        val match = NodeFinder.find(root, WaSelectors.ComposeBox, target.packageName) ?: return false
        val text = match.node.text?.toString().orEmpty()
        return text.isBlank() || text.equals(HINT_TEXT, ignoreCase = true)
    }

    /** Any of WhatsApp's ban / restriction / can't-send wording currently on screen. */
    private fun restrictionIn(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val visible = NodeFinder.collectVisibleText(root).joinToString(" ")
        return WaSelectors.matchFragment(visible, WaSelectors.RESTRICTION_TEXT_FRAGMENTS)
    }

    private fun openChat(context: Context, target: WaPackage, e164: String, text: String): Boolean =
        try {
            val url = WaDeepLink.chatUrl(e164, text)
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(target.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${target.packageName}", e)
            false
        } catch (e: IllegalArgumentException) {
            // WaDeepLink rejects a number with no digits in it.
            Log.w(TAG, "bad number for deep link", e)
            false
        }

    /** WhatsApp reports its placeholder as the node's text on some builds. */
    private const val HINT_TEXT = "Type a message"
}
