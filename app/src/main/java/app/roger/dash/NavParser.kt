package app.roger.dash

import java.util.Calendar
import kotlin.math.roundToInt

/** What the dash should show for the next step of a Google Maps route. */
data class NavStep(
    val icon: Int,
    val toTurnM: Int?,
    val remainingM: Int?,
    val eta: Calendar?,
    val instruction: String,
    val status: Char = '1', // '1' navigating, '2' re-routing, '5' arrived
    /** Road the next turn goes onto (our own routes only). */
    val street: String? = null,
    /** Which turn this is along the route, to tell turns apart (-1 = unknown). */
    val turnId: Int = -1,
    /** Spoken early ("Turn left onto MG Road") and at the turn itself. */
    val verbalAlert: String? = null,
    val verbalNow: String? = null,
)

/**
 * Turns Google Maps' navigation notification text into a [NavStep]. Google Maps puts the distance
 * to the next turn and the instruction in the title/text ("350 m" / "Turn right onto MG Road"),
 * and time, distance and arrival in the sub-text ("12 min · 5.2 km · ETA 1:05 am").
 * The wording varies by version and language, so this is keyword-based and forgiving.
 */
object NavParser {
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""(\d{1,2})[:.](\d{2})\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(?:(\d+)\s*(?:hr|h|hour|hours))?\s*(\d+)\s*(?:min|mins|minutes)\b""", RegexOption.IGNORE_CASE)
    private val EXIT = Regex("""(\d+)(?:st|nd|rd|th)\s+exit""", RegexOption.IGNORE_CASE)
    private val EXIT_WORD = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7)

    fun parse(title: String?, text: String?, sub: String?, now: Calendar = Calendar.getInstance()): NavStep? {
        val t = title.orEmpty().trim()
        val x = text.orEmpty().trim()
        val s = sub.orEmpty().trim()
        if (t.isEmpty() && x.isEmpty()) return null

        // Distance to the turn: usually the title on its own ("350 m"); otherwise the first distance seen.
        val toTurn = DIST.find(t)?.let { metres(it) } ?: DIST.find(x)?.let { metres(it) }
        // The instruction is whichever line isn't just a distance.
        val instruction = listOf(t, x).firstOrNull { it.isNotEmpty() && DIST.replace(it, "").trim(' ', '·', '-').isNotEmpty() } ?: t

        val remaining = DIST.find(s)?.let { metres(it) }
        val eta = clock(s, now) ?: MINUTES.find(s)?.let { m ->
            val mins = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + m.groupValues[2].toInt()
            (now.clone() as Calendar).apply { add(Calendar.MINUTE, mins) }
        }

        val all = "$t $x".lowercase()
        val status = when {
            "rerouting" in all || "recalculating" in all || "re-routing" in all -> '2'
            "arrived" in all || "you have arrived" in all -> '5'
            else -> '1'
        }
        return NavStep(icon(all), toTurn, remaining, eta, instruction, status)
    }

    /** Dashboard icon for an instruction (see [Frames.Icon]). */
    fun icon(text: String): Int {
        val s = text.lowercase()
        fun has(vararg w: String) = w.any { it in s }
        val left = has("left")
        val right = has("right")
        return when {
            has("destination", "arrive", "arrived") -> Frames.Icon.ARRIVE
            has("u-turn", "u turn", "make a u") -> Frames.Icon.U_TURN
            has("roundabout", "rotary", "traffic circle") -> {
                val n = EXIT.find(s)?.groupValues?.get(1)?.toIntOrNull()
                    ?: EXIT_WORD.entries.firstOrNull { "${it.key} exit" in s }?.value
                Frames.Icon.ROUNDABOUT_EXIT_1 + ((n ?: 1) - 1).coerceIn(0, 6)
            }
            has("sharp left") -> Frames.Icon.SHARP_LEFT
            has("sharp right") -> Frames.Icon.SHARP_RIGHT
            has("keep left", "fork left", "stay left") -> Frames.Icon.KEEP_LEFT
            has("keep right", "fork right", "stay right") -> Frames.Icon.KEEP_RIGHT
            has("slight left", "slightly left", "bear left") -> Frames.Icon.SLIGHT_LEFT
            has("slight right", "slightly right", "bear right") -> Frames.Icon.SLIGHT_RIGHT
            has("exit", "ramp") && right -> Frames.Icon.OFF_RAMP_RIGHT
            has("exit", "ramp") && left -> Frames.Icon.SLIGHT_LEFT
            has("end of the road", "end of road") && left -> Frames.Icon.END_OF_ROAD_LEFT
            has("end of the road", "end of road") && right -> Frames.Icon.END_OF_ROAD_RIGHT
            left && !right -> Frames.Icon.LEFT
            right && !left -> Frames.Icon.RIGHT
            has("continue", "straight", "head", "go ") -> Frames.Icon.STRAIGHT
            else -> Frames.Icon.STRAIGHT
        }
    }

    private fun metres(m: MatchResult): Int {
        val v = m.groupValues[1].replace(',', '.').toDouble()
        return when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000
            "mi" -> v * 1609.34
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> v
        }.roundToInt()
    }

    /** "ETA 1:05 am" / "1:05 PM" / "13:05" → today (or tomorrow) at that time. */
    private fun clock(s: String, now: Calendar): Calendar? {
        val m = CLOCK.findAll(s).lastOrNull() ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ap = m.groupValues[3].lowercase().replace(".", "").replace(" ", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}
