package app.roger.dash

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Suzuki Ride Connect dashboard frames, worked out from the official app's own packet log and code for
 * interoperability. Suzuki's app uses the same frames for all its Ride Connect dashes; RogerDash has
 * only been tried on a V-Strom 250 SX.
 *
 * Every frame is 30 bytes: 0xA5, ASCII type, 26 body bytes, checksum, 0x7F.
 * Checksum = (sum of bytes 1..27) and 0xFF. (Older Access/Burgman "SAS…" dashes invert it; not handled,
 * see [DashModel.legacyDash].)
 */
object Frames {
    const val SIZE = 30

    /**
     * Turn icons (byte 2 of a navigation frame). All of 1-46 checked on the real V-Strom dash (2026-11-14
     * photos): 7 = U-turn LEFT, 39 = U-turn RIGHT, 9 = bullseye, 11/12 = junction left/right (road carries on),
     * 38 and 44 = off-ramp left, 20-26 = clockwise roundabout with the exit drawn at 45° steps
     * (20 ≈ first exit on the left … 23 = straight across … 26 ≈ almost all the way round), 45 = enter roundabout, 46 = "!".
     */
    object Icon {
        const val LEFT = 1
        const val SHARP_LEFT = 2
        const val SLIGHT_LEFT = 3
        const val RIGHT = 4
        const val SHARP_RIGHT = 5
        const val SLIGHT_RIGHT = 6
        const val U_TURN = 7          // drawn as a LEFT U-turn
        const val U_TURN_RIGHT = 39
        const val JUNCTION_LEFT = 11
        const val JUNCTION_RIGHT = 12
        const val OFF_RAMP_LEFT = 44
        const val ENTER_ROUNDABOUT = 45
        const val FERRY = 43
        const val STRAIGHT = 8
        const val ARRIVE = 9
        const val OFF_RAMP_RIGHT = 10
        const val END_OF_ROAD_LEFT = 13
        const val END_OF_ROAD_RIGHT = 14
        const val ROUNDABOUT_EXIT_1 = 20 // exits 1..7 = 20..26
        const val KEEP_LEFT = 27
        const val KEEP_RIGHT = 28
        const val CONTINUE = 33
        const val REROUTING = 46
    }

    fun frame(type: Char, body: ByteArray): ByteArray {
        require(body.size == 26)
        val f = ByteArray(SIZE)
        f[0] = 0xA5.toByte()
        f[1] = type.code.toByte()
        body.copyInto(f, 2)
        f[28] = checksum(f)
        f[29] = 0x7F
        return f
    }

    fun checksum(f: ByteArray): Byte {
        var s = 0
        for (i in 1..27) s += f[i].toInt() and 0xFF
        return (s and 0xFF).toByte()
    }

    fun isValid(f: ByteArray) =
        f.size == SIZE && f[0] == 0xA5.toByte() && f[29] == 0x7F.toByte() && f[28] == checksum(f)

    private fun body() = ByteArray(26) { 0xFF.toByte() }

    /** Writes [s] as plain ASCII into the body at frame offset [off], null-padded to [len]. */
    private fun ByteArray.text(off: Int, len: Int, s: String) {
        val clean = s.map { if (it.code in 32..126) it else ' ' }.joinToString("").take(len - 1)
        for (i in 0 until len) this[off - 2 + i] = if (i < clean.length) clean[i].code.toByte() else 0
    }

    private fun ByteArray.ascii(off: Int, s: String) {
        for (i in s.indices) this[off - 2 + i] = s[i].code.toByte()
    }

    private fun ByteArray.byte(off: Int, v: Int) { this[off - 2] = v.toByte() }

    /** '6': rider name, sent once after connecting. [reconnect]: same dashboard as last time. */
    fun rider(name: String, reconnect: Boolean): ByteArray {
        val b = body()
        b.text(2, 20, name)
        b.byte(27, if (reconnect) 'R'.code else 'F'.code)
        return frame('6', b)
    }

    /**
     * '3': phone status and clock; the keep-alive, about once a second.
     * [messagesRead] / [missedCleared]: 'Y' for a few frames tells the dash to clear its message / missed-call
     * icon (Suzuki's app does this when they've been read on the phone).
     */
    fun status(batteryBars: Int, charging: Boolean, signalBars: Int, now: Calendar = Calendar.getInstance(),
               messagesRead: Boolean = false, missedCleared: Boolean = false): ByteArray {
        val b = body()
        b.byte(2, '0'.code + batteryBars.coerceIn(0, 3))
        b.byte(3, if (charging) 'Y'.code else 'N'.code)
        // 4..6 speed alert: FF FF FF = off
        b.byte(7, if (signalBars <= 0) 0 else '0'.code + signalBars.coerceIn(1, 3))
        val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        b.ascii(8, "%02d%02d%02d".format(h, now.get(Calendar.MINUTE), now.get(Calendar.SECOND)))
        b.byte(14, if (messagesRead) 'Y'.code else 'N'.code)  // messages-read flag
        b.byte(15, if (missedCleared) 'Y'.code else 'N'.code) // missed-calls-cleared flag
        b.byte(21, 0)        // weather code (not shown on V-Strom)
        b.byte(22, 0)        // temperature
        b.byte(23, 0x01)
        return frame('3', b)
    }

    /**
     * '1': navigation. [toTurnM] metres to the next turn, [remainingM] metres to the destination,
     * [eta] arrival time. [status]: '1' navigating, '2' re-routing, '4' GPS lost, '5' arrived.
     */
    fun nav(icon: Int, toTurnM: Int, remainingM: Int, eta: Calendar, status: Char = '1', active: Boolean = true): ByteArray {
        val b = body()
        b.byte(2, if (status in "135") icon else Icon.REROUTING)
        val (d1, u1) = distance(((toTurnM + 5) / 10) * 10)
        b.ascii(4, d1); b.byte(8, u1.code)
        b.ascii(9, clock(eta))
        val (d2, u2) = distance(remainingM)
        b.ascii(18, d2); b.byte(22, u2.code)
        b.byte(23, status.code)
        b.byte(24, if (active) '1'.code else '0'.code)
        return frame('1', b)
    }

    /** Navigation finished: send this several times, like the official app. */
    fun navEnd(): ByteArray = nav(Icon.ARRIVE, 0, 0, Calendar.getInstance(), status = '5', active = false)

    /**
     * '2': incoming (ringing) or ongoing call. There is no "call ended" frame: Suzuki's app just stops
     * sending, and the dash goes back by itself.
     */
    fun call(caller: String, ringing: Boolean, whatsapp: Boolean = false): ByteArray {
        val b = body()
        b.text(2, 20, caller.replace("+", ""))
        b.byte(22, if (whatsapp) 'W'.code else 'N'.code)
        b.byte(23, if (ringing) '1'.code else '2'.code)
        return frame('2', b)
    }

    /** '4': missed call(s). */
    fun missedCall(count: Int, caller: String, whatsapp: Boolean = false): ByteArray {
        val b = body()
        b.byte(3, count.coerceIn(0, 255))
        b.text(4, 20, caller.replace("+", ""))
        b.byte(24, if (whatsapp) 'W'.code else 'N'.code)
        return frame('4', b)
    }

    /** '5': message from [sender]; [isNew] false clears it. Kind 'N' SMS, 'W' WhatsApp, 'X' calendar. */
    fun message(sender: String, unread: Int, isNew: Boolean = true, kind: Char = 'N'): ByteArray {
        val b = body()
        b.byte(3, if (isNew) 'Y'.code else 'N'.code)
        b.byte(4, unread.coerceIn(0, 255))
        b.text(5, 20, sender)
        b.byte(25, kind.code)
        return frame('5', b)
    }

    /** 4 characters + unit, as the dashboard expects: "0350"M, "01.2"K, "12.5"K, "0123"K. */
    fun distance(m: Int): Pair<String, Char> {
        if (m < 1000) return "%04d".format(java.util.Locale.US, m.coerceAtLeast(0)) to 'M'
        val km = (m / 100.0).roundToInt() / 10.0
        return if (km < 100) "%04.1f".format(java.util.Locale.US, km) to 'K'
        else "%04d".format(java.util.Locale.US, km.roundToInt().coerceAtMost(9999)) to 'K'
    }

    /** "hhmmAM" / "hhmmPM". */
    fun clock(c: Calendar): String {
        val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return "%02d%02d%s".format(h, c.get(Calendar.MINUTE), if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
    }

    /** '7' from the dashboard: live bike data. */
    /**
     * [fuelRaw]: bytes 25-27. Suzuki's app reads them as a fuel-economy value, but on the V-Strom SX only byte 25
     * is used (26-27 = FF), and it behaves like **engine temperature in °C** (ride 2026-11-14: 34-35 at key-on on a
     * warm afternoon, climbing to 105 while riding, falling slowly once stopped). It is *not* the trip average
     * (dash showed trip B AVG 32.8 km/l). See [engineTempC].
     */
    data class BikeData(val speedKmh: Int?, val odoKm: Int?, val tripAKm: Double?, val tripBKm: Double?, val fuelBars: Int?,
                        val fuelRaw: Int? = null) {
        val economy: Double? get() = fuelRaw?.let { it / 2048.0 / 10.0 }

        /** Byte 25 when 26-27 are unused (FF FF): engine temperature, °C. */
        val engineTempC: Int? get() = fuelRaw?.takeIf { it and 0xFFFF == 0xFFFF }?.let { it shr 16 }?.takeIf { it in 1..200 }
    }

    fun parseBike(f: ByteArray): BikeData? {
        if (f.size < SIZE || f[0] != 0xA5.toByte() || f[1] != '7'.code.toByte()) return null
        fun num(from: Int, len: Int) = String(f, from, len, Charsets.US_ASCII).trim().toIntOrNull()
        return BikeData(
            speedKmh = num(2, 3),
            odoKm = num(5, 6),
            tripAKm = num(11, 6)?.div(10.0),
            tripBKm = num(17, 6)?.div(10.0),
            fuelBars = (f[24] - '0'.code.toByte()).takeIf { it in 0..9 },
            fuelRaw = ((f[25].toInt() and 0xFF) shl 16 or ((f[26].toInt() and 0xFF) shl 8) or (f[27].toInt() and 0xFF))
                .takeIf { it != 0xFFFFFF },
        )
    }

    fun hex(f: ByteArray) = f.joinToString(" ") { "%02X".format(it) }
}
