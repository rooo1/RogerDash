package app.roger.dash

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** One turn instruction along a route. */
data class Maneuver(
    val icon: Int,
    val instruction: String,
    val beginIndex: Int,   // index into Route.shape where this maneuver happens
    val type: Int,
    /** Road this maneuver turns onto, e.g. "MG Road" (null if unnamed). */
    val street: String? = null,
    /** Spoken versions from Valhalla: said early ("Turn left onto MG Road") and at the turn. */
    val verbalAlert: String? = null,
    val verbalNow: String? = null,
    /** Reaching a stop along the way (not the final destination). */
    val isStop: Boolean = false,
)

data class Route(
    val shape: List<LatLon>, val maneuvers: List<Maneuver>, val lengthM: Double, val timeSec: Double,
    /** Traffic per piece of [shape] (piece i = shape[i]→shape[i+1]): 0 clear, 1 slow, 2 jam. Empty = unknown. */
    val traffic: List<Int> = emptyList(),
)

/** Kind of route a rider wants. */
enum class RouteStyle(val label: String, val about: String) {
    FASTEST("Fastest", "Quickest with live traffic"),
    SHORTEST("Shortest", "Fewest km, even if slower"),
    SCENIC("Scenic", "Twistier smaller roads, no highways; up to ~35% longer"),
    NO_HIGHWAYS("No highways", "Stays off highways and expressways"),
}

/** What to avoid when planning (Settings → Navigation), and the kind of route. */
data class RouteOptions(
    val avoidTolls: Boolean = false, val avoidHighways: Boolean = false, val avoidUnpaved: Boolean = false,
    val style: RouteStyle = RouteStyle.FASTEST,
) {
    /** Scenic and no-highway routes leave highways out whatever the avoid switch says. */
    val noHighways get() = avoidHighways || style == RouteStyle.NO_HIGHWAYS || style == RouteStyle.SCENIC
}

/**
 * Routing with Valhalla (OpenStreetMap data, FOSSGIS public server; free, no key), using the
 * motorcycle profile (falls back to car if that fails).
 */
object Router {
    private const val SERVER = "https://valhalla1.openstreetmap.de/route"

    /** Where the last route came from ("Google" / "OpenStreetMap"), shown on the preview. */
    @Volatile var lastSource = ""

    /**
     * Blocking; call off the main thread. [heading]: the way the rider is going (degrees), so a re-route
     * continues forward instead of starting with "turn around".
     */
    fun route(from: LatLon, to: LatLon, heading: Float? = null, options: RouteOptions = RouteOptions()): Route =
        route(listOf(from, to), heading, options)

    /**
     * Route through [points]: start, any stops in order, destination. Blocking. [heading] as above.
     */
    fun route(points: List<LatLon>, heading: Float? = null, options: RouteOptions = RouteOptions()): Route {
        require(points.size >= 2)
        val err = ArrayList<String>()
        // Shortest and scenic pick from several candidate routes (Google's alternatives and OpenStreetMap's).
        val choosing = options.style == RouteStyle.SHORTEST || options.style == RouteStyle.SCENIC
        val candidates = ArrayList<Pair<Route, String>>()
        // With the rider's Google key: Google two-wheeler routes with traffic. ("Avoid unpaved" is OpenStreetMap-only.)
        val key = DashHub.googleKey
        if (key.isNotBlank() && !options.avoidUnpaved) {
            try {
                val g = GoogleRoutes.routes(DashHub.app, key, points, heading, options, alternatives = choosing)
                GoogleAuth.worked()
                if (!choosing) return g.first().also { lastSource = "Google" }
                g.forEach { candidates += it to "Google" }
            } catch (e: Exception) {
                GoogleAuth.failed(e)
                err += "Google: ${e.message}"
                DashHub.onLog("Google route failed (${e.message}); using OpenStreetMap")
            }
        }
        for (costing in listOf("motorcycle", "auto")) {
            try {
                val o = JSONObject(Http.get(url(points, costing, heading, options, alternates = if (choosing) 2 else 0)))
                candidates += parse(o) to "OpenStreetMap"
                o.optJSONArray("alternates")?.let { alts -> for (i in 0 until alts.length()) runCatching { candidates += parse(alts.getJSONObject(i)) to "OpenStreetMap" } }
                break
            } catch (e: Exception) {
                err += "$costing: ${e.message}"
            }
        }
        if (candidates.isEmpty()) throw IllegalStateException("No route (${err.joinToString("; ")})")
        val (best, source) = pick(candidates, options.style)
        lastSource = source
        return best
    }

    /** Shortest: fewest metres. Scenic: the twistiest (most turning per km) of those taking at most 35% longer than the quickest. */
    fun <T> pick(c: List<Pair<Route, T>>, style: RouteStyle): Pair<Route, T> = when (style) {
        RouteStyle.SHORTEST -> c.minBy { it.first.lengthM }
        RouteStyle.SCENIC -> {
            val quickest = c.minOf { it.first.timeSec }
            c.filter { it.first.timeSec <= quickest * 1.35 }.maxBy { twistiness(it.first.shape) }
        }
        else -> c.first()
    }

    /** Degrees of turning per km, counting only bends (heading changes of 10–120° between ~40 m pieces). */
    fun twistiness(shape: List<LatLon>): Double {
        if (shape.size < 3) return 0.0
        val pts = ArrayList<LatLon>().apply { add(shape.first()) }
        for (p in shape) if (Geo.dist(pts.last(), p) >= 40) pts += p
        var turn = 0.0; var m = 0.0
        for (i in 1 until pts.size) {
            m += Geo.dist(pts[i - 1], pts[i])
            if (i >= 2) {
                val d = Geo.angle(Geo.bearing(pts[i - 2], pts[i - 1]), Geo.bearing(pts[i - 1], pts[i]))
                if (d in 10.0..120.0) turn += d // junction turns (sharper) don't make a road scenic
            }
        }
        return if (m > 0) turn / (m / 1000) else 0.0
    }

    private fun url(points: List<LatLon>, costing: String, heading: Float?, o: RouteOptions, alternates: Int = 0): String {
        val locs = JSONArray()
        points.forEachIndexed { i, p ->
            val l = JSONObject().put("lat", p.lat).put("lon", p.lon)
            if (i == 0 && heading != null) l.put("heading", heading.toInt()).put("heading_tolerance", 45)
            locs.put(l)
        }
        val req = JSONObject()
            .put("locations", locs)
            .put("costing", costing)
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "en-US"))
        val c = JSONObject()
        if (o.avoidTolls) c.put("use_tolls", 0.0).put("exclude_tolls", true)
        if (o.noHighways) c.put("use_highways", 0.0)
        if (o.avoidUnpaved) c.put("exclude_unpaved", true)
        if (o.style == RouteStyle.SHORTEST) c.put("shortest", true)
        if (c.length() > 0) req.put("costing_options", JSONObject().put(costing, c))
        if (alternates > 0 && points.size == 2) req.put("alternates", alternates)
        return "$SERVER?json=${URLEncoder.encode(req.toString(), "UTF-8")}"
    }

    /** All legs joined into one route; a stop's "you have arrived" becomes a stop maneuver. */
    fun parse(o: JSONObject): Route {
        val trip = o.getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        val shape = ArrayList<LatLon>()
        val list = ArrayList<Maneuver>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val legShape = Geo.decode(leg.getString("shape"), 6)
            // Legs share their joining point: drop the duplicate.
            val offset = if (shape.isEmpty()) 0 else shape.size - 1
            shape.addAll(if (shape.isEmpty()) legShape else legShape.drop(1))
            val last = li == legs.length() - 1
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val type = m.getInt("type")
                val local = m.getInt("begin_shape_index").coerceIn(0, legShape.lastIndex)
                val begin = (offset + local).coerceIn(0, shape.lastIndex)
                val exitAt = if (type == 26) (i + 1 until ms.length()).map { ms.getJSONObject(it) }
                    .firstOrNull { it.getInt("type") == 27 }?.getInt("begin_shape_index")?.let { (offset + it).coerceIn(0, shape.lastIndex) } else null
                val stop = !last && type in 4..6
                Maneuver(
                    // Roundabout: draw the exit where it really is, from the angle between entering and leaving.
                    icon = exitAt?.let { roundaboutIcon(shape, begin, it) } ?: icon(type, m.optInt("roundabout_exit_count", 1)),
                    instruction = if (stop) "Arrive at stop ${li + 1}" else m.optString("instruction").trimEnd('.'),
                    beginIndex = begin,
                    type = type,
                    street = m.optJSONArray("street_names")?.optString(0)?.takeIf { it.isNotBlank() }
                        ?: m.optJSONArray("begin_street_names")?.optString(0)?.takeIf { it.isNotBlank() },
                    verbalAlert = if (stop) "Your stop is ahead" else m.optString("verbal_transition_alert_instruction").ifBlank { null },
                    verbalNow = if (stop) "You've reached your stop" else m.optString("verbal_pre_transition_instruction").ifBlank { null },
                    isStop = stop,
                ).let { list += it }
            }
        }
        val sum = trip.getJSONObject("summary")
        return Route(shape, list, sum.getDouble("length") * 1000, sum.getDouble("time"))
    }

    /** Valhalla maneuver type → dashboard icon (see [Frames.Icon]). */
    fun icon(type: Int, exit: Int = 1): Int = when (type) {
        4, 5, 6 -> Frames.Icon.ARRIVE
        9, 37 -> Frames.Icon.SLIGHT_RIGHT
        10 -> Frames.Icon.RIGHT
        11 -> Frames.Icon.SHARP_RIGHT
        12 -> Frames.Icon.U_TURN_RIGHT   // the usual U-turn in India (left-hand traffic)
        13 -> Frames.Icon.U_TURN         // left U-turn
        14 -> Frames.Icon.SHARP_LEFT
        15 -> Frames.Icon.LEFT
        16, 38 -> Frames.Icon.SLIGHT_LEFT
        18 -> Frames.Icon.RIGHT          // "Turn right to take the ramp"
        19 -> Frames.Icon.LEFT           // "Turn left to take the ramp"
        20 -> Frames.Icon.OFF_RAMP_RIGHT // "Take the exit on the right"
        21 -> Frames.Icon.OFF_RAMP_LEFT  // "Take the exit on the left"
        23 -> Frames.Icon.KEEP_RIGHT
        24 -> Frames.Icon.KEEP_LEFT
        26 -> if (exit in 1..7) Frames.Icon.ROUNDABOUT_EXIT_1 + exit - 1 else Frames.Icon.ENTER_ROUNDABOUT
        28 -> Frames.Icon.FERRY
        else -> Frames.Icon.STRAIGHT // start, continue, becomes, ramp straight, stay straight, merge
    }

    /**
     * Roundabout icon from geometry. The dash draws a clockwise roundabout (left-hand traffic) entered from
     * the bottom, with the exit at 45° steps: travel round ≤45° → 20, ≤90° (left turn) → 21, … ≤180°
     * (straight on) → 23, … ≤270° (right turn) → 25, more → 26.
     */
    fun roundaboutIcon(shape: List<LatLon>, enter: Int, exit: Int): Int? {
        if (exit <= enter) return null
        val inFrom = back(shape, enter, 25.0)
        val outTo = ahead(shape, exit, 25.0)
        if (Geo.dist(inFrom, shape[enter]) < 5 || Geo.dist(shape[exit], outTo) < 5) return null
        val turn = ((Geo.bearing(shape[exit], outTo) - Geo.bearing(inFrom, shape[enter])) + 540) % 360 - 180 // -180..180, right +
        val round = (180 + turn).let { if (it <= 0) it + 360 else it }  // degrees travelled round, clockwise
        val bucket = ((round - 0.01) / 45).toInt().coerceIn(0, 6)
        return Frames.Icon.ROUNDABOUT_EXIT_1 + bucket
    }

    private fun back(s: List<LatLon>, i: Int, m: Double): LatLon {
        var j = i; while (j > 0 && Geo.dist(s[j], s[i]) < m) j--; return s[j]
    }

    private fun ahead(s: List<LatLon>, i: Int, m: Double): LatLon {
        var j = i; while (j < s.lastIndex && Geo.dist(s[j], s[i]) < m) j++; return s[j]
    }
}
