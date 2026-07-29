package com.tricreta.scopewa.accessibility

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.tricreta.scopewa.brain.reply.RawNotification
import com.tricreta.scopewa.brain.reply.ReplyNotificationParser
import com.tricreta.scopewa.data.repository.campaign.CampaignRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The eyes for incoming replies — architecture doc section 6 layer 3.
 *
 * ## Why a notification listener and not the Accessibility service
 *
 * The Accessibility service can only read a screen that is *on screen*. A
 * campaign runs for hours on a phone nobody is touching (section 10, Q4), so
 * reading the chat list would mean either driving WhatsApp to the foreground
 * every few minutes — visible, ban-flavoured behaviour that fights the pacing
 * layer — or missing every reply that arrives while the app is backgrounded,
 * which is all of them. A notification listener sees the reply the moment it
 * lands, with WhatsApp closed and the screen off.
 *
 * ## This class is deliberately almost empty
 *
 * Everything that could be wrong — is this WhatsApp, is this a real message or
 * a "3 new messages from 2 chats" roll-up, where does the sender's name end and
 * the message begin — is decided by
 * [ReplyNotificationParser] and
 * [com.tricreta.scopewa.brain.reply.ReplyRouter], which are pure Kotlin and
 * fully unit tested. A `NotificationListenerService` cannot be tested in CI at
 * all, so the less judgement it carries the better. All it does is lift fields
 * out of a `Bundle` and hand them over.
 *
 * ## Privacy
 *
 * Message bodies are never logged and never stored. The body exists in memory
 * only long enough for `OptOutDetector` to answer yes or no; what reaches the
 * database is a reply count, a timestamp and — for an opt-out — the single
 * matched keyword.
 */
class WaNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Built lazily and defensively: the system can bind this service at boot,
     * before anything else in the app has run, and a Room failure here must not
     * take down a system-bound service.
     */
    private var repository: CampaignRepository? = null

    private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!connected) return
        val statusBarNotification = sbn ?: return

        val raw = lift(statusBarNotification) ?: return
        val reply = ReplyNotificationParser.parse(raw) ?: return
        val repo = repositoryOrNull() ?: return

        scope.launch {
            runCatching { repo.handleIncomingReply(reply) }
                .onFailure {
                    // Deliberately no message and no exception body: this runs
                    // over other people's notifications and logcat is readable
                    // by anyone with adb.
                    Log.w(TAG, "could not record an incoming reply")
                }
        }
    }

    /** Pulls the fields the parser needs out of the notification's extras. */
    @Suppress("DEPRECATION") // Notification.priority: still the only signal for min-priority notices.
    private fun lift(sbn: StatusBarNotification): RawNotification? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        return RawNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isSilent = notification.priority <= Notification.PRIORITY_MIN,
            postedAtMillis = sbn.postTime
        )
    }

    private fun repositoryOrNull(): CampaignRepository? {
        repository?.let { return it }
        val context = applicationContext ?: return null
        return runCatching { CampaignRepository.create(context) }
            .onSuccess { repository = it }
            .getOrNull()
    }

    private companion object {
        const val TAG = "WaNotificationListener"
    }
}
