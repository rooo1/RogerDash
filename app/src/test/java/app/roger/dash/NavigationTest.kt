package app.roger.dash

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class NavigationTest {

    private fun encode(points: List<LatLon>, precision: Int = 6): String {
        val f = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1F).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = (p.lat * f).roundToLong(); val lon = (p.lon * f).roundToLong()
            put(lat - pLat); put(lon - pLon); pLat = lat; pLon = lon
        }
        return sb.toString()
    }

    /** Scenic picks the twistier route unless it's much slower; shortest picks the fewest metres. */
    @Test fun routeStylesPickTheRightCandidate() {
        val straight = (0..50).map { LatLon(12.9 + it * 0.001, 77.6) }
        val zigzag = (0..50).map { LatLon(12.9 + it * 0.001, 77.6 + if (it % 2 == 0) 0.0 else 0.0006) }
        assertTrue(Router.twistiness(zigzag) > Router.twistiness(straight) + 50)
        val fast = Route(straight, emptyList(), 5600.0, 400.0)
        val twisty = Route(zigzag, emptyList(), 7000.0, 500.0)    // 25% slower: allowed
        val tooSlow = Route(zigzag, emptyList(), 7000.0, 900.0)  // more than 35% slower: not allowed
        assertSame(twisty, Router.pick(listOf(fast to "g", twisty to "o"), RouteStyle.SCENIC).first)
        assertSame(fast, Router.pick(listOf(fast to "g", tooSlow to "o"), RouteStyle.SCENIC).first)
        assertSame(fast, Router.pick(listOf(twisty to "g", fast to "o"), RouteStyle.SHORTEST).first)
        assertTrue(RouteOptions(style = RouteStyle.SCENIC).noHighways)
    }

    @Test fun moveGoesTheRightDistance() {
        val p = LatLon(16.06, 73.47)
        listOf(0.0, 60.0, 200.0).forEach { b -> assertEquals(20_000.0, Geo.dist(p, Geo.move(p, b, 20_000.0)), 150.0) }
    }

    @Test fun decodesGoogleExamplePolyline() {
        val p = Geo.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-5); assertEquals(-120.2, p[0].lon, 1e-5)
        assertEquals(43.252, p[2].lat, 1e-5); assertEquals(-126.453, p[2].lon, 1e-5)
    }

    /** A 2 km road due north with a right turn after 1 km, as Valhalla would describe it. */
    private fun valhalla(): JSONObject {
        val pts = (0..20).map { LatLon(12.90 + it * 0.0009, 77.60) } // ~100 m apart
        val man = JSONArray()
            .put(JSONObject().put("type", 1).put("instruction", "Drive north.").put("begin_shape_index", 0))
            .put(JSONObject().put("type", 10).put("instruction", "Turn right onto Ring Road.").put("begin_shape_index", 10))
            .put(JSONObject().put("type", 4).put("instruction", "You have arrived at your destination.").put("begin_shape_index", 20))
        val leg = JSONObject().put("shape", encode(pts)).put("maneuvers", man)
        return JSONObject().put("trip", JSONObject()
            .put("legs", JSONArray().put(leg))
            .put("summary", JSONObject().put("length", 2.0).put("time", 240.0)))
    }

    @Test fun parsesValhallaRoute() {
        val r = Router.parse(valhalla())
        assertEquals(21, r.shape.size)
        assertEquals(Frames.Icon.RIGHT, r.maneuvers[1].icon)
        assertEquals("Turn right onto Ring Road", r.maneuvers[1].instruction)
        assertEquals(Frames.Icon.ARRIVE, r.maneuvers[2].icon)
        assertEquals(2000.0, r.lengthM, 0.1)
    }

    /** Roundabout entered heading north: leaving west = left (21), north = straight (23), east = right (25). */
    @Test fun roundaboutIconFollowsExitAngle() {
        val enter = (0..5).map { LatLon(12.90 + it * 0.0001, 77.60) } // 5 points north, ~11 m apart
        fun exitTo(dLat: Double, dLon: Double) = enter + (1..5).map { LatLon(enter.last().lat + it * dLat, 77.60 + it * dLon) }
        assertEquals(21, Router.roundaboutIcon(exitTo(0.0, -0.0001), 4, 5))
        assertEquals(23, Router.roundaboutIcon(exitTo(0.0001, 0.0), 4, 5))
        assertEquals(25, Router.roundaboutIcon(exitTo(0.0, 0.0001), 4, 5))
    }

    /** Two legs (via a stop): shapes joined without the duplicate point, the stop marked, indices offset. */
    @Test fun routeWithAStopJoinsLegs() {
        fun leg(from: Int, to: Int, last: Boolean): JSONObject {
            val pts = (from..to).map { LatLon(12.90 + it * 0.0009, 77.60) }
            val man = JSONArray()
                .put(JSONObject().put("type", 1).put("instruction", "Drive north.").put("begin_shape_index", 0)
                    .put("verbal_pre_transition_instruction", "Drive north."))
                .put(JSONObject().put("type", 4).put("instruction", "You have arrived.").put("begin_shape_index", to - from))
            return JSONObject().put("shape", encode(pts)).put("maneuvers", man)
        }
        val o = JSONObject().put("trip", JSONObject()
            .put("legs", JSONArray().put(leg(0, 10, false)).put(leg(10, 20, true)))
            .put("summary", JSONObject().put("length", 2.0).put("time", 240.0)))
        val r = Router.parse(o)
        assertEquals(21, r.shape.size)
        val stop = r.maneuvers.single { it.isStop }
        assertEquals(10, stop.beginIndex)
        assertEquals("Arrive at stop 1", stop.instruction)
        assertEquals(20, r.maneuvers.last().beginIndex)
        assertTrue(!r.maneuvers.last().isStop)
    }

    /** Google Routes: steps joined, the turn sits where its step starts, arrival added, street taken from the text. */
    @Test fun parsesGoogleRoute() {
        val a = (0..5).map { LatLon(12.90 + it * 0.0009, 77.60) }          // north
        val b = (0..5).map { LatLon(12.90 + 5 * 0.0009, 77.60 + it * 0.0009) } // then east
        fun step(m: String, text: String, pts: List<LatLon>) = JSONObject()
            .put("navigationInstruction", JSONObject().put("maneuver", m).put("instructions", text))
            .put("polyline", JSONObject().put("encodedPolyline", encode(pts, 5)))
        val o = JSONObject().put("routes", JSONArray().put(JSONObject()
            .put("distanceMeters", 1000).put("duration", "180s")
            .put("legs", JSONArray().put(JSONObject().put("steps", JSONArray()
                .put(step("DEPART", "Head north", a))
                .put(step("TURN_RIGHT", "Turn right onto Ring Rd/ NH 48\nPass by the mall", b)))))))
        // Traffic: overall polyline = the same 11 points; points 5..10 (the eastern part) jammed.
        o.getJSONArray("routes").getJSONObject(0)
            .put("polyline", JSONObject().put("encodedPolyline", encode(a + b.drop(1), 5)))
            .put("travelAdvisory", JSONObject().put("speedReadingIntervals", JSONArray()
                .put(JSONObject().put("startPolylinePointIndex", 0).put("endPolylinePointIndex", 5).put("speed", "NORMAL"))
                .put(JSONObject().put("startPolylinePointIndex", 5).put("endPolylinePointIndex", 10).put("speed", "TRAFFIC_JAM"))))
        val r = GoogleRoutes.parse(o)
        assertEquals(listOf(0, 0, 0, 0, 0, 2, 2, 2, 2, 2), r.traffic)
        assertEquals(11, r.shape.size)
        val turn = r.maneuvers[1]
        assertEquals(Frames.Icon.RIGHT, turn.icon)
        assertEquals(5, turn.beginIndex)
        assertEquals("Ring Rd", turn.street)
        assertEquals(Frames.Icon.ARRIVE, r.maneuvers.last().icon)
        assertEquals(180.0, r.timeSec, 0.1)
    }

    @Test fun spokenDistances() {
        assertEquals("300 metres", Voice.spoken(290))
        assertEquals("1.5 kilometres", Voice.spoken(1520))
    }

    @Test fun uTurnsMatchTheDash() {
        assertEquals(Frames.Icon.U_TURN_RIGHT, Router.icon(12))
        assertEquals(Frames.Icon.U_TURN, Router.icon(13))
        assertEquals(Frames.Icon.OFF_RAMP_LEFT, Router.icon(21))
    }

    @Test fun countsDownToTheNextTurn() {
        val r = Router.parse(valhalla())
        val nav = Navigator(Place("Cafe", "", r.shape.last()), r) {}
        val s = nav.onLocation(LatLon(12.90 + 3 * 0.0009, 77.60), 5f) // ~300 m along
        assertEquals(Frames.Icon.RIGHT, s.icon)
        assertTrue("to turn ${s.toTurnM}", abs(s.toTurnM!! - 700) < 15)
        assertTrue("remaining ${s.remainingM}", abs(s.remainingM!! - 1700) < 30)
        assertEquals('1', s.status)
    }

    @Test fun afterTheTurnShowsArrival() {
        val r = Router.parse(valhalla())
        val nav = Navigator(Place("Cafe", "", r.shape.last()), r) {}
        val s = nav.onLocation(LatLon(12.90 + 15 * 0.0009, 77.60), 5f)
        assertEquals(Frames.Icon.ARRIVE, s.icon)
        val end = nav.onLocation(r.shape.last(), 5f)
        assertEquals('5', end.status)
    }

    /** Wrong turn: at the junction (1 km north) the rider goes east instead of on north. Still within 40 m of the route. */
    @Test fun wrongTurnAtJunctionIsCaughtByHeading() {
        val r = Router.parse(valhalla())
        var asked = 0
        val nav = Navigator(Place("Cafe", "", r.shape.last()), r) { asked++ }
        val junction = 12.90 + 10 * 0.0009
        nav.onLocation(LatLon(junction - 0.0005, 77.60), 5f, 0f, 8f) // heading north on the route
        for (east in listOf(0.00015, 0.00020, 0.00025, 0.00030)) // ~16-33 m east, heading east
            nav.onLocation(LatLon(junction, 77.60 + east), 5f, 90f, 8f)
        assertEquals(1, asked)
    }

    @Test fun ridingAlongTheRouteWithHeadingStaysOnIt() {
        val r = Router.parse(valhalla())
        var asked = 0
        val nav = Navigator(Place("Cafe", "", r.shape.last()), r) { asked++ }
        for (k in 1..9) nav.onLocation(LatLon(12.90 + k * 0.0009, 77.60001), 8f, 2f, 8f)
        assertEquals(0, asked)
        assertTrue(!nav.offRoute)
    }

    @Test fun leavingTheRouteAsksForANewOne() {
        val r = Router.parse(valhalla())
        var asked = 0
        val nav = Navigator(Place("Cafe", "", r.shape.last()), r) { asked++ }
        val off = LatLon(12.90 + 3 * 0.0009, 77.602) // ~200 m east of the road
        repeat(3) { nav.onLocation(off, 5f) }
        assertEquals(1, asked)
        assertEquals('2', nav.onLocation(off, 5f).status)
    }
}
