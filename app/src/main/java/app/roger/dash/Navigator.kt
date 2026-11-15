package app.roger.dash

import android.os.SystemClock
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Follows the rider along a [Route]: snaps each GPS fix onto the route, finds the next turn,
 * counts down the distance to it, estimates arrival, and asks for a new route when the rider leaves it.
 */
class Navigator(val dest: Place, route: Route, private val needReroute: (LatLon) -> Unit) {
    private companion object {
        const val OFF_ROUTE_M = 40.0
        /** Moving at an angle this far from the road we're snapped to = not on it (crossing road, wrong turn). */
        const val WRONG_HEADING = 70.0
        const val NEAR_M = 12.0       // within this, heading doesn't matter (GPS bearing lags in the turn itself)
        const val MOVING_MPS = 3.0f   // ~11 km/h: below this GPS bearing is noise
        const val OFF_FIXES = 3
        const val REROUTE_GAP_MS = 8_000L
        const val ARRIVED_M = 35.0
    }

    var route: Route = route
        private set
    private var cum = cumulative(route.shape)
    private var seg = 0
    private var along = 0.0
    private var offCount = 0
    private var lastReroute = 0L
    var rerouting = false
        private set
    var arrived = false
        private set
    /** Last fix wasn't on the route (wrong turn, or GPS far off). */
    var offRoute = false
        private set

    /** Where we are on the route (GPS snapped onto the road), or null when off it / before the first fix. */
    var snapped: LatLon? = null
        private set
    /** Direction of the road at [snapped], degrees. */
    var roadBearing: Double? = null
        private set

    /** Traffic for the pieces of [ahead] (same order), or empty if unknown. */
    fun aheadTraffic(): List<Int> {
        val t = route.traffic
        if (t.isEmpty()) return t
        return if (snapped == null) t else t.drop(seg.coerceAtMost(t.size))
    }

    /** The part of the route still ahead, starting at our snapped position (for drawing). */
    fun ahead(): List<LatLon> {
        val s = route.shape
        val from = snapped ?: return s
        return listOf(from) + s.subList((seg + 1).coerceAtMost(s.lastIndex), s.size)
    }

    /** Counts re-routes, so turn ids from different routes never collide. */
    private var routeNo = 0

    fun setRoute(r: Route) {
        route = r
        routeNo++
        cum = cumulative(r.shape)
        seg = 0; along = 0.0; offCount = 0
        rerouting = false; offRoute = false; snapped = null
    }

    private fun cumulative(s: List<LatLon>) = DoubleArray(s.size).also { c ->
        for (i in 1 until s.size) c[i] = c[i - 1] + Geo.dist(s[i - 1], s[i])
    }

    /** Before the first GPS fix: the first real instruction. */
    fun initial(): NavStep = step()

    /**
     * [bearing]/[speedMps] from GPS (null if unknown). When moving, a fix only counts as "on the route" if
     * we're travelling along it, not across it or the wrong way: that is what catches a wrong turn at a
     * junction quickly, while the rider is still physically close to the planned route.
     */
    fun onLocation(p: LatLon, accuracyM: Float, bearing: Float? = null, speedMps: Float? = null): NavStep {
        val s = route.shape
        if (s.size >= 2 && !rerouting) {
            val moving = bearing != null && speedMps != null && speedMps >= MOVING_MPS
            // Search a window around the last position (fast, and avoids snapping to a parallel road far ahead).
            // Score = distance, plus a penalty for road pieces pointing another way than we're going.
            var best = Double.MAX_VALUE; var bestScore = Double.MAX_VALUE; var bestSeg = seg; var bestT = 0.0
            var bestOff = 0.0
            for (i in max(0, seg - 5) until minOf(s.size - 1, seg + 150)) {
                val (d, t) = Geo.toSegment(p, s[i], s[i + 1])
                val off = if (moving && Geo.dist(s[i], s[i + 1]) > 1) Geo.angle(Geo.bearing(s[i], s[i + 1]), bearing!!.toDouble()) else 0.0
                val score = d + if (off > WRONG_HEADING) 60.0 else 0.0
                if (score < bestScore) { bestScore = score; best = d; bestSeg = i; bestT = t; bestOff = off }
            }
            val wrongWay = moving && bestOff > WRONG_HEADING && best > NEAR_M
            val offRoute = (best > OFF_ROUTE_M && accuracyM < 40f) || wrongWay
            if (offRoute) offCount++ else offCount = 0
            if (!offRoute && best <= OFF_ROUTE_M * 1.5) {
                seg = bestSeg
                along = cum[bestSeg] + (cum[bestSeg + 1] - cum[bestSeg]) * bestT
                val a = s[bestSeg]; val b = s[bestSeg + 1]
                snapped = LatLon(a.lat + (b.lat - a.lat) * bestT, a.lon + (b.lon - a.lon) * bestT)
                roadBearing = if (Geo.dist(a, b) > 1) Geo.bearing(a, b) else roadBearing
            } else snapped = null
            this.offRoute = offRoute
            val now = SystemClock.elapsedRealtime()
            if (offCount >= OFF_FIXES && (lastReroute == 0L || now - lastReroute > REROUTE_GAP_MS)) {
                lastReroute = now.coerceAtLeast(1)
                rerouting = true
                offCount = 0
                needReroute(p)
            }
        }
        return step()
    }

    /** Metres travelled along the route so far. */
    val alongM: Double get() = along

    /** A new route is being worked out on purpose (stop added): show "re-routing" until it arrives. */
    fun forceReroute() { rerouting = true; lastReroute = SystemClock.elapsedRealtime() }

    /** Re-route failed (e.g. no internet): keep guiding on the old route. */
    fun rerouteFailed() { rerouting = false }

    private fun step(): NavStep {
        val total = cum.lastOrNull() ?: 0.0
        val remaining = (total - along).coerceAtLeast(0.0)
        val eta = Calendar.getInstance().apply {
            add(Calendar.SECOND, if (total > 0) (route.timeSec * remaining / total).roundToInt() else 0)
        }
        if (rerouting) return NavStep(Frames.Icon.REROUTING, null, remaining.roundToInt(), eta, "Finding a new route…", '2')
        if (remaining < ARRIVED_M || arrived) {
            arrived = true
            return NavStep(Frames.Icon.ARRIVE, 0, 0, eta, "You've arrived at ${dest.name}", '5')
        }
        // Next maneuver ahead of us (skip the "start" instruction once moving).
        val next = route.maneuvers.firstOrNull { cum[it.beginIndex] > along + 5 && it.type !in 1..3 }
            ?: route.maneuvers.last()
        val toTurn = (cum[next.beginIndex] - along).coerceAtLeast(0.0)
        return NavStep(next.icon, toTurn.roundToInt(), remaining.roundToInt(), eta, next.instruction,
            street = next.street, turnId = route.maneuvers.indexOf(next) + routeNo * 1000,
            verbalAlert = next.verbalAlert ?: next.instruction, verbalNow = next.verbalNow ?: next.instruction)
    }
}
