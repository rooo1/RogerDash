package app.roger.dash

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads three kinds of notifications, and ignores everything else:
 *  - Google Maps' turn-by-turn notification: each step goes to [DashHub] for the dash;
 *  - the phone app's incoming-call notification (the caller's name), WhatsApp calls;
 *  - SMS and WhatsApp messages (who it's from, not the text): [PhoneAlerts].
 * Needs "Notification access" (Settings), granted once.
 */
class NavListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"

        fun allowed(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NavListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private var navKey: String? = null
    private var lastRaw = ""

    override fun onListenerConnected() {
        DashHub.init(this)
        // Notifications already on the phone: pick up a running Google Maps navigation; old messages are
        // only noted (so they aren't sent to the dash as new ones), not sent.
        replaying = true
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
        replaying = false
    }

    private var replaying = false

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) { alert(sbn); return }
        if (!isNavigation(sbn)) return
        if (DashHub.navigator != null) return // our own navigation is running
        navKey = sbn.key
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val raw = "title=$title | text=$text | sub=$sub"
        if (raw != lastRaw) {
            lastRaw = raw
            DashHub.lastMapsRaw = raw
            DashHub.onLog("Maps: $raw") // the exact wording helps tune the parser
        }
        NavParser.parse(title, text, sub)?.let { DashHub.onNav(it) }
    }

    private val smsApp by lazy { runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull() }

    /** Calls and messages for the dash. */
    private fun alert(sbn: StatusBarNotification) {
        val n = sbn.notification
        val e = n.extras
        PhoneAlerts.onPosted(sbn.key, PhoneAlerts.Note(
            pkg = sbn.packageName,
            category = n.category,
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ongoing = sbn.isOngoing,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        ), smsApp, send = !replaying)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) { PhoneAlerts.onRemoved(sbn.key); return }
        if (sbn.key == navKey) {
            navKey = null
            lastRaw = ""
            DashHub.onNavEnded()
        }
    }

    /** Google Maps' ongoing navigation notification (not traffic or place notifications). */
    private fun isNavigation(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        val ch = n.channelId.orEmpty().lowercase()
        return sbn.isOngoing && ("navigation" in ch || "nav" in ch)
    }
}
