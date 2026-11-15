package app.roger.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Calls and messages on the dash, like Suzuki's app: incoming calls with the caller's name, missed calls,
 * and who an SMS or WhatsApp message is from (never the message text).
 *
 * - Phone calls: Android's call state (needs the Phone permission) says ringing / answered / ended; the
 *   caller's name is read from the phone app's incoming-call notification, so no Contacts or call-log access.
 * - WhatsApp calls, SMS and WhatsApp messages: from their notifications ([NavListener], Notification access).
 *
 * Names and numbers are never logged or stored; they only go to the dash.
 */
@SuppressLint("MissingPermission")
object PhoneAlerts {

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    private val SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms")

    // ───────────── notification → what it means (pure, unit-tested) ─────────────

    /** The parts of a notification that matter here. */
    data class Note(
        val pkg: String,
        val category: String?,
        val title: String?,
        val text: String?,
        val ongoing: Boolean = false,
        val groupSummary: Boolean = false,
    )

    sealed class Alert {
        data class CallRinging(val caller: String, val whatsapp: Boolean) : Alert()
        data class MissedCall(val caller: String, val whatsapp: Boolean) : Alert()
        data class Message(val sender: String, val whatsapp: Boolean) : Alert()
    }

    fun isWhatsApp(pkg: String) = pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS

    /** What [n] is for the dash, or null. [smsApp]: the phone's default SMS app. */
    fun classify(n: Note, smsApp: String?): Alert? {
        if (n.groupSummary) return null // "5 messages from 3 chats": the chats come separately
        val wa = isWhatsApp(n.pkg)
        val title = n.title?.trim().orEmpty()
        val text = n.text?.trim().orEmpty()
        when (n.category) {
            Notification.CATEGORY_CALL -> {
                // A phone call's name comes with the call state ([onCallName]); here only WhatsApp calls.
                if (!wa) return null
                return name(title, text)?.let { Alert.CallRinging(it, true) }
            }
            Notification.CATEGORY_MISSED_CALL -> {
                if (!wa) return null // phone missed calls come from the call state
                return name(title, text)?.let { Alert.MissedCall(it, true) }
            }
        }
        if (n.ongoing) return null
        val sms = n.pkg == smsApp || n.pkg in SMS_APPS
        if (!sms && !wa) return null
        if (n.category != null && n.category != Notification.CATEGORY_MESSAGE) return null
        if (title.isEmpty()) return null
        return Alert.Message(title, wa)
    }

    /**
     * The person's name from a call notification: "Asha" / "Incoming voice call" → "Asha";
     * "Missed voice call" / "Asha" → "Asha". Null when neither line looks like a name.
     */
    fun name(title: String, text: String): String? {
        fun generic(s: String) = s.isEmpty() || Regex("(?i)\\b(incoming|missed|ongoing|calling|call|ringing|whatsapp)\\b").containsMatchIn(s) &&
            !Regex("\\d{5,}").containsMatchIn(s)
        return when {
            !generic(title) -> title
            !generic(text) -> text
            else -> null
        }
    }

    // ───────────── phone calls (call state) ─────────────

    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var ringing = false
    private var answered = false
    private var callerName: String? = null
    private var missedCount = 0
    private var messages = 0
    private var waMissed = 0

    fun hasPhonePermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Starts listening to the call state once the Phone permission is allowed (safe to call again). */
    fun start(ctx: Context) {
        if (registered || !hasPhonePermission(ctx)) return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            tm.registerTelephonyCallback(ctx.mainExecutor, callState)
            registered = true
        }.onFailure { DashHub.onLog("Can't watch calls: ${it.message}") }
    }

    private val callState = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    ringing = true; answered = false
                    sendCall(ringingNow = true)
                    // The phone app's notification brings the name a moment later ([onCallName]).
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> if (ringing) { answered = true; sendCall(ringingNow = false) }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (ringing && !answered) {
                        missedCount++
                        if (forwardCalls()) {
                            repeat(3) { DashHub.link.send(Frames.missedCall(missedCount, callerName ?: "Missed call")) }
                            DashHub.onLog("Missed call → dash")
                        }
                    }
                    ringing = false; answered = false; callerName = null
                }
            }
        }
    }

    /** The phone app's incoming-call notification: who is calling. */
    fun onCallName(n: Note) {
        if (!ringing || isWhatsApp(n.pkg) || n.category != Notification.CATEGORY_CALL) return
        val who = name(n.title.orEmpty().trim(), n.text.orEmpty().trim()) ?: return
        if (who == callerName) return
        callerName = who
        sendCall(ringingNow = !answered)
    }

    private fun forwardCalls() = DashHub.connected && !DashHub.demo && DashHub.model.suzuki && DashHub.dashCalls
    private fun forwardMessages() = DashHub.connected && !DashHub.demo && DashHub.model.suzuki && DashHub.dashMessages

    private fun sendCall(ringingNow: Boolean) {
        if (!forwardCalls()) return
        val f = Frames.call(callerName ?: "Call", ringing = ringingNow)
        repeat(3) { DashHub.link.send(f) }
        DashHub.onLog(if (ringingNow) "Incoming call → dash" else "Call answered → dash")
    }

    // ───────────── notifications ─────────────

    /** Message or WhatsApp-call notifications still on the phone (keys), to clear the dash icon once they're gone. */
    private val shownMessages = HashSet<String>()
    private val lastText = HashMap<String, String>()
    private val shownMissed = HashSet<String>()

    /** [send] false: only note it (notifications already on the phone when the listener starts). */
    fun onPosted(key: String, n: Note, smsApp: String?, send: Boolean = true) {
        // The phone app's own missed-call notification: when it's dismissed, the dash clears its icon too.
        if (n.category == Notification.CATEGORY_MISSED_CALL && !isWhatsApp(n.pkg)) shownMissed += key
        val a = classify(n, smsApp)
        if (!send) {
            if (a is Alert.Message) { lastText[key] = "${n.title}|${n.text}"; shownMessages += key }
            if (a is Alert.MissedCall) shownMissed += key
            return
        }
        onCallName(n)
        when (a) {
            is Alert.CallRinging -> if (forwardCalls()) {
                repeat(3) { DashHub.link.send(Frames.call(a.caller, ringing = true, whatsapp = true)) }
                DashHub.onLog("WhatsApp call → dash")
            }
            is Alert.MissedCall -> {
                shownMissed += key
                if (forwardCalls()) {
                    waMissed++
                    repeat(3) { DashHub.link.send(Frames.missedCall(waMissed, a.caller, whatsapp = true)) }
                    DashHub.onLog("WhatsApp missed call → dash")
                }
            }
            is Alert.Message -> {
                // Updates of the same notification with the same text (silent re-posts) aren't new messages.
                val sig = "${n.title}|${n.text}"
                if (lastText[key] == sig) return
                lastText[key] = sig
                shownMessages += key
                if (!forwardMessages()) return
                messages++
                DashHub.link.send(Frames.message(a.sender, messages, isNew = true, kind = if (a.whatsapp) 'W' else 'N'))
                DashHub.onLog("${if (a.whatsapp) "WhatsApp message" else "SMS"} → dash")
            }
            null -> {}
        }
    }

    /** A message read or dismissed on the phone: once none are left, the dash clears its message icon. */
    fun onRemoved(key: String) {
        lastText.remove(key)
        if (shownMessages.remove(key) && shownMessages.isEmpty()) {
            messages = 0
            DashHub.link.messagesReadFrames = 3
        }
        if (shownMissed.remove(key) && shownMissed.isEmpty()) {
            waMissed = 0; missedCount = 0
            DashHub.link.missedClearedFrames = 3
        }
    }

    // ───────────── tests (Dash test tools and setup) ─────────────

    fun testCall() = repeat(3) { DashHub.link.send(Frames.call("RogerDash test", ringing = true)) }
    fun testMessage() = repeat(3) { DashHub.link.send(Frames.message("RogerDash", 1)) }
    fun clearTests() {
        repeat(3) { DashHub.link.send(Frames.message("RogerDash", 0, isNew = false)) }
        DashHub.link.messagesReadFrames = 3
    }
}
