package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Checks our frames byte-for-byte against frames captured from the official app. */
class FramesTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun navigationFrameMatchesCapture() {
        // Official app, destination reached: icon 9, 0000M, ETA 12:06 AM, 0000M, status '5', active.
        val captured = bytes("A5 31 09 FF 30 30 30 30 4D 31 32 30 36 41 4D FF FF FF 30 30 30 30 4D 35 31 FF FF FF 0A 7F")
        val eta = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 6) }
        val ours = Frames.nav(Frames.Icon.ARRIVE, 0, 0, eta, status = '5', active = true)
        assertEquals(Frames.hex(captured), Frames.hex(ours))
    }

    @Test fun messageClearFrameMatchesCapture() {
        // Official app: SMS notification from contact "Papa" removed (flag 'N'), 0 unread, kind SMS.
        val captured = bytes("A5 35 FF 4E 00 50 61 70 61 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 4E FF FF 50 7F")
        assertEquals(Frames.hex(captured), Frames.hex(Frames.message("Papa", 0, isNew = false, kind = 'N')))
    }

    @Test fun bikeFrameParsesFuelField() {
        // speed 042, odo 012337, trip A 000635, trip B 004127, byte 23 unused, 4 bars, economy 32.5 km/l (13.11 fixed / 10)
        val raw = (32.5 * 10 * 2048).toInt()
        val body = "042012337000635004127".toByteArray() + byteArrayOf(0xFF.toByte(), '4'.code.toByte(),
            (raw shr 16).toByte(), (raw shr 8).toByte(), raw.toByte())
        val b = Frames.parseBike(Frames.frame('7', body))!!
        assertEquals(42, b.speedKmh); assertEquals(12337, b.odoKm); assertEquals(63.5, b.tripAKm!!, 0.01)
        assertEquals(4, b.fuelBars); assertEquals(32.5, b.economy!!, 0.001)
        assertEquals(null, b.engineTempC) // all three bytes used: not the temperature form
    }

    /** Real V-Strom frame (2026-11-14): byte 25 = engine °C, bytes 26-27 = FF. */
    @Test fun bikeFrameEngineTemperature() {
        val body = "000004667002930026584".toByteArray() + byteArrayOf(0xFF.toByte(), '3'.code.toByte(), 0x69, 0xFF.toByte(), 0xFF.toByte())
        val b = Frames.parseBike(Frames.frame('7', body))!!
        assertEquals(105, b.engineTempC)
        assertEquals(3, b.fuelBars)
    }

    @Test fun streetNamesFitTheDash() {
        assertEquals("MG RD", Dash12.street("MG Road"))
        assertEquals("M GANDHI RD", Dash12.street("Mahatma Gandhi Road"))
        assertEquals("NH 48", Dash12.street("National Highway 48"))
        assertTrue(Dash12.street("Lal Bahadur Shastri Marg Service Road").length <= 12)
        assertTrue(Range.dashText(150).length <= 12 && Range.dashText(35).length <= 12 && Range.refuelText(120).length <= 12)
    }

    @Test fun riderFrameLayout() {
        val f = Frames.rider("TEST", reconnect = true)
        assertTrue(Frames.isValid(f))
        assertEquals('6'.code.toByte(), f[1])
        assertEquals('R'.code.toByte(), f[27])
        assertEquals(0xFF.toByte(), f[22])
    }

    @Test fun distanceFormat() {
        assertEquals("0350" to 'M', Frames.distance(350))
        assertEquals("01.2" to 'K', Frames.distance(1200))
        assertEquals("12.5" to 'K', Frames.distance(12_500))
        assertEquals("0100" to 'K', Frames.distance(99_960))
        assertEquals("0123" to 'K', Frames.distance(123_000))
    }

    @Test fun allFramesValid() {
        listOf(
            Frames.status(2, false, 3), Frames.call("Someone", true), Frames.missedCall(1, "Someone"),
            Frames.navEnd(),
        ).forEach { assertTrue(Frames.hex(it), Frames.isValid(it)) }
    }
}
