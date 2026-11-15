package app.roger.dash

import android.app.Notification
import app.roger.dash.PhoneAlerts.Alert
import app.roger.dash.PhoneAlerts.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAlertsTest {
    private val sms = "com.samsung.android.messaging"

    @Test fun smsFromDefaultApp() {
        assertEquals(Alert.Message("Papa", false),
            PhoneAlerts.classify(Note(sms, Notification.CATEGORY_MESSAGE, "Papa", "Where are you?"), sms))
        // Another SMS app set as default, no category
        assertEquals(Alert.Message("Asha", false), PhoneAlerts.classify(Note("org.example.sms", null, "Asha", "hi"), "org.example.sms"))
    }

    @Test fun whatsAppMessageAndSummary() {
        assertEquals(Alert.Message("Riders group", true),
            PhoneAlerts.classify(Note(PhoneAlerts.WHATSAPP, Notification.CATEGORY_MESSAGE, "Riders group", "Asha: leaving now"), sms))
        assertNull(PhoneAlerts.classify(Note(PhoneAlerts.WHATSAPP, Notification.CATEGORY_MESSAGE, "WhatsApp", "5 messages from 3 chats",
            groupSummary = true), sms))
    }

    @Test fun otherAppsIgnored() {
        assertNull(PhoneAlerts.classify(Note("com.google.android.gm", Notification.CATEGORY_EMAIL, "Bank", "Statement"), sms))
        assertNull(PhoneAlerts.classify(Note("com.spotify.music", Notification.CATEGORY_TRANSPORT, "Song", "Artist", ongoing = true), sms))
        // Ongoing notification from the SMS app (e.g. "sending…") isn't a message
        assertNull(PhoneAlerts.classify(Note(sms, null, "Sending", "", ongoing = true), sms))
    }

    @Test fun whatsAppCalls() {
        assertEquals(Alert.CallRinging("Asha", true),
            PhoneAlerts.classify(Note(PhoneAlerts.WHATSAPP, Notification.CATEGORY_CALL, "Asha", "Incoming voice call", ongoing = true), sms))
        assertEquals(Alert.MissedCall("Asha", true),
            PhoneAlerts.classify(Note(PhoneAlerts.WHATSAPP, Notification.CATEGORY_MISSED_CALL, "Missed voice call", "Asha"), sms))
    }

    @Test fun phoneCallNotificationsComeFromCallState() {
        // The dialer's call notification only supplies the name ([PhoneAlerts.onCallName]), not an alert by itself.
        assertNull(PhoneAlerts.classify(Note("com.samsung.android.incallui", Notification.CATEGORY_CALL, "Asha", "Incoming call", ongoing = true), sms))
        assertNull(PhoneAlerts.classify(Note("com.samsung.android.dialer", Notification.CATEGORY_MISSED_CALL, "Missed call", "Asha"), sms))
    }

    @Test fun callerName() {
        assertEquals("Asha", PhoneAlerts.name("Asha", "Incoming call"))
        assertEquals("Asha", PhoneAlerts.name("Incoming call", "Asha"))
        assertEquals("+91 98200 12345", PhoneAlerts.name("+91 98200 12345", "Incoming call"))
        assertNull(PhoneAlerts.name("Incoming call", ""))
    }

    @Test fun callFrameDropsPlus() {
        val f = Frames.call("+919820012345", ringing = true)
        assertTrue(Frames.isValid(f))
        assertEquals("919820012345", String(f, 2, 12, Charsets.US_ASCII))
    }

    @Test fun statusClearFlags() {
        val plain = Frames.status(2, false, 3)
        val cleared = Frames.status(2, false, 3, messagesRead = true, missedCleared = true)
        assertEquals('N'.code.toByte(), plain[14]); assertEquals('N'.code.toByte(), plain[15])
        assertEquals('Y'.code.toByte(), cleared[14]); assertEquals('Y'.code.toByte(), cleared[15])
        assertTrue(Frames.isValid(cleared))
    }

    @Test fun models() {
        assertTrue(DashModel.VSTROM_250_SX.tested)
        assertFalse(DashModel.OTHER_SUZUKI.tested)
        assertEquals("Other Suzuki (not tested yet)", DashModel.OTHER_SUZUKI.label)
        assertEquals(DashModel.NONE, DashModel.of("none"))
        assertTrue(DashModel.legacyDash("SAS01AB12345"))
        assertFalse(DashModel.legacyDash("SBM010213457"))
        val v = Vehicle.defaultFor(DashModel.VSTROM_250_SX)
        assertEquals(12.0, v.tankL, 0.0); assertEquals(6, v.bars); assertTrue(v.dash)
        assertFalse(Vehicle.defaultFor(DashModel.NONE).dash)
        assertEquals(0, Vehicle.defaultFor(DashModel.NONE).bars)
        assertTrue(Dash12.street("Lal Bahadur Shastri Marg", max = 8).length <= 8)
    }
}
