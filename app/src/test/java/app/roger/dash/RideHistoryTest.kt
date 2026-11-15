package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RideHistoryTest {

    /** Navigate to A (arrive), then A → B (cancelled midway): two sub-trips, the second "from A". */
    @org.junit.Test fun recorderMakesSubTrips() {
        val r = RideRecorder()
        r.seed(0.5, 60, 30)              // riding before any navigation
        r.startLeg("Metro Junction Mall")
        r.seed(2.0, 300, 55)
        r.endLeg("arrived")
        r.startLeg("Kalyan Station")
        r.seed(1.2, 200, 48)
        r.endLeg("cancelled")            // stopped navigating here
        r.startLeg("Nowhere"); r.endLeg("cancelled") // started and cancelled on the spot: not a sub-trip
        val t = r.finish()!!
        org.junit.Assert.assertEquals(2, t.legs.size)
        org.junit.Assert.assertEquals(2.0, t.legs[0].distanceKm, 0.01)
        org.junit.Assert.assertEquals("arrived", t.legs[0].ended)
        org.junit.Assert.assertEquals("Metro Junction Mall", t.legs[1].from)
        org.junit.Assert.assertEquals(1.2, t.legs[1].distanceKm, 0.01)
        org.junit.Assert.assertEquals("cancelled", t.legs[1].ended)
        org.junit.Assert.assertEquals(3.7, t.distanceKm, 0.01) // the main trip is still the whole ride
    }

    /** Sub-trips survive saving and loading, and cut the right piece of the track. */
    @org.junit.Test fun legsRoundTripAndTrack() {
        val pts = (0..10).map { Pt(12.9 + it * 0.001, 77.6, it * 60, 30f) }
        val legs = listOf(
            Leg("Metro Junction Mall", null, 1000L, 2000L, 120, 300, 2.4, 170, 55, "arrived"),
            Leg("Station", "Metro Junction Mall", 3000L, 4000L, 420, 540, 1.1, 100, 40, "cancelled"),
        )
        val t = Trip(1L, "Ride", 0L, 600_000L, 5.0, 400, 60, null, null, null, null, "gps", points = pts, legs = legs)
        val back = Trip.fromJson(t.toJson())
        org.junit.Assert.assertEquals(legs, back.legs)
        org.junit.Assert.assertEquals(listOf(120, 180, 240, 300), back.legs[0].track(pts).map { it.t })
        org.junit.Assert.assertTrue(!back.legs[1].arrived)
        // Old rides without legs still load.
        val old = org.json.JSONObject(t.toJson().toString()).apply { remove("legs") }
        org.junit.Assert.assertTrue(Trip.fromJson(old).legs.isEmpty())
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 10) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, 0) }.timeInMillis

    private fun trip(start: Long, km: Double, moving: Int = 1800, pts: List<Pt> = emptyList(), name: String = "Ride") =
        Trip(start, name, start, start + moving * 1000L, km, moving, 80, null, null, null, null, "gps", points = pts)

    @Test fun gpxHasTracksPointsAndUtcTimes() {
        val start = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        val t = trip(start, 1.2, pts = listOf(Pt(12.9716, 77.5946, 0, 30f), Pt(12.9726, 77.5956, 60, 40f)), name = "Night <ride> & chai")
        val g = Gpx.of(listOf(t, trip(start + 1, 3.0))) // second ride has no route: left out
        assertTrue(g.startsWith("<?xml"))
        assertTrue(g.contains("""<gpx version="1.1" creator="RogerDash" xmlns="http://www.topografix.com/GPX/1/1">"""))
        assertEquals(1, Regex("<trk>").findAll(g).count())
        assertEquals(2, Regex("<trkpt ").findAll(g).count())
        assertTrue(g.contains("""<trkpt lat="12.971600" lon="77.594600"><time>2023-11-14T22:13:20Z</time></trkpt>"""))
        assertTrue(g.contains("<time>2023-11-14T22:14:20Z</time>")) // +60 s
        assertTrue(g.contains("Night &lt;ride&gt; &amp; chai"))
        assertTrue(g.trimEnd().endsWith("</gpx>"))
    }

    @Test fun monthlyReportCountsOnlyThatMonth() {
        val trips = listOf(
            trip(at(2026, Calendar.NOVEMBER, 2), 40.0, 3600),
            trip(at(2026, Calendar.NOVEMBER, 14), 120.0, 7200),
            trip(at(2026, Calendar.OCTOBER, 31, 23), 500.0),
            trip(at(2026, Calendar.DECEMBER, 1, 0), 70.0),
        )
        val fuel = listOf(
            FuelEntry(at(2026, Calendar.OCTOBER, 20), 10.0, 1000.0, 4000),
            FuelEntry(at(2026, Calendar.NOVEMBER, 5), 8.0, 840.0, 4300),  // 300 km on 8 L
            FuelEntry(at(2026, Calendar.NOVEMBER, 13), 6.0, 630.0, 4480), // 180 km on 6 L
        )
        val r = MonthReport.of(2026, Calendar.NOVEMBER, trips, fuel)
        assertEquals(2, r.rides.size)
        assertEquals(160.0, r.km, 0.01)
        assertEquals(10800, r.movingSec)
        assertEquals(120.0, r.longest!!.distanceKm, 0.01)
        assertEquals(14.0, r.fuelLitres, 0.01)
        assertEquals(1470.0, r.fuelCost, 0.01)
        assertEquals(480.0 / 14.0, r.kmPerL!!, 0.01)
    }

    @Test fun monthWithoutFillUpsHasNoMileage() {
        val r = MonthReport.of(2026, Calendar.SEPTEMBER, emptyList(), listOf(FuelEntry(at(2026, Calendar.SEPTEMBER, 3), 9.0, 900.0, 100)))
        assertNull(r.kmPerL) // first fill-up ever: nothing to measure yet
        assertEquals(9.0, r.fuelLitres, 0.01)
        assertNull(r.longest)
    }

    @Test fun trackFindsTopSpeedAndStops() {
        val pts = listOf(
            Pt(12.9700, 77.5900, 0, 20f),
            Pt(12.9703, 77.5900, 10, 55f),   // ~33 m on
            Pt(12.9704, 77.5900, 400, 0f),   // 390 s later, ~11 m: a stop
            Pt(12.9710, 77.5900, 420, 81f),
        )
        val t = trip(at(2026, Calendar.NOVEMBER, 14), 1.0, pts = pts)
        assertEquals(81f, Track.topPoint(t)!!.kmh)
        val stops = Track.stops(t)
        assertEquals(1, stops.size)
        assertEquals(390, stops[0].sec)
        assertFalse(Track.stops(trip(0, 1.0)).isNotEmpty())
    }

    @Test fun oldTripsWithoutTrackStillLoad() {
        val o = org.json.JSONObject("""{"id":1,"name":"Old","start":1,"end":2,"distanceKm":3.5,"movingSec":60,"topKmh":40,"source":"bike"}""")
        val t = Trip.fromJson(o)
        assertNotNull(t)
        assertTrue(t.points.isEmpty())
        assertEquals("", Gpx.of(listOf(t)).let { if (it.contains("<trk>")) "has track" else "" })
    }

    /** Full-tank method: 4,500 full → 4,650 full with 4.5 L = 33.33 km/l; a top-up in between counts towards the litres. */
    @Test fun fullTankMileage() {
        val log = listOf(
            FuelEntry(1, 10.0, 1000.0, 4500, full = true),
            FuelEntry(2, 4.5, 450.0, 4650, full = true),
            FuelEntry(3, 2.0, 200.0, 4700, full = false), // top-up
            FuelEntry(4, 3.0, 300.0, 4800, full = true),  // 150 km on 2 + 3 L = 30 km/l
        )
        assertEquals(150.0 / 4.5, Economy.at(log, 1)!!, 0.01)
        assertNull(Economy.at(log, 2))                 // top-up: nothing measured there
        assertEquals(30.0, Economy.at(log, 3)!!, 0.01)
        val e = Economy.of(log)
        assertEquals(2, e.intervals)
        assertEquals(300.0 / 9.5, e.kmPerL!!, 0.01)
        assertEquals(950.0 / 300.0, e.costPerKm!!, 0.01)
        assertEquals(100.0, Economy.pricePerL(log)!!, 0.01)
        // Top-up first, no full tank before it: not measurable yet.
        assertNull(Economy.of(listOf(FuelEntry(1, 2.0, 0.0, 100, full = false), FuelEntry(2, 5.0, 0.0, 200, full = true))).kmPerL)
    }

    @Test fun monthlyFuelSpend() {
        val log = listOf(
            FuelEntry(at(2026, Calendar.NOVEMBER, 2), 5.0, 500.0, 1),
            FuelEntry(at(2026, Calendar.NOVEMBER, 12), 5.0, 520.0, 2),
            FuelEntry(at(2026, Calendar.SEPTEMBER, 20), 5.0, 480.0, 0),
        )
        val m = Economy.monthly(log, 3, at(2026, Calendar.NOVEMBER, 14))
        assertEquals(listOf(480.0, 0.0, 1020.0), m.map { it.second })
    }

    @Test fun tripFuelAndRefuels() {
        val dir = java.nio.file.Files.createTempDirectory("store").toFile()
        val store = TripStore(android.content.ContextWrapper(null), dir) // stub context: only the folder is used
        store.addFuel(FuelEntry(1, 10.0, 1000.0, 1000)); store.addFuel(FuelEntry(2, 10.0, 1000.0, 1300)); store.addFuel(FuelEntry(3, 10.0, 1050.0, 1600))
        // Measured 30 km/l over 2 tanks; ₹105/L.
        val f = Range.forTrip(300.0, 150, store)
        assertEquals(10.0, f.litres, 0.01)
        assertEquals(1050.0, f.cost!!, 0.1)
        assertEquals(1, f.refuels)          // 150 km range − 25 margin = 125, then a tank does ~300
        assertEquals(125, f.firstRefuelByKm)
        assertEquals(0, Range.forTrip(100.0, 150, store).refuels)
    }

    /** A scooter's fill-ups and rides don't mix with the Suzuki's; its range counts down from the last full tank. */
    @Test fun scooterRangeFromKmSinceFullTank() {
        val dir = java.nio.file.Files.createTempDirectory("store").toFile()
        val store = TripStore(android.content.ContextWrapper(null), dir)
        val scooter = Vehicle("s1", "Activa", dash = false, tankL = 5.0, typicalKmpl = 45.0)
        val now = System.currentTimeMillis()
        store.addFuel(FuelEntry(now - 10 * 86_400_000L, 10.0, 1000.0, 1000))                 // Suzuki
        store.addFuel(FuelEntry(now - 3 * 86_400_000L, 4.0, 420.0, 8000, true, "s1"))         // scooter, full
        store.save(trip(now - 2 * 86_400_000L, 30.0).copy(vehicle = "s1"))
        store.save(trip(now - 86_400_000L, 15.0).copy(vehicle = "s1"))
        store.save(trip(now - 86_400_000L + 1, 99.0))                                         // Suzuki ride: not counted
        val e = Range.fromLog(store, scooter, ridingKm = 5.0)!!
        assertEquals(45.0, e.kmPerL, 0.01)                // no measured tank yet: the expected figure
        assertEquals(225 - 50, e.km)                      // 5 L × 45 − (30 + 15 + 5)
        assertEquals(Range.Confidence.LOW, e.confidence)
        assertEquals(1, store.fuel("s1").size)
        assertEquals(2, store.trips("s1").size)
        assertNull(Range.fromLog(store, scooter.copy(id = "none")))  // no full tank logged
        // Saved and loaded with its vehicle.
        assertEquals("s1", Trip.fromJson(store.trips("s1").first().toJson()).vehicle)
    }

    @Test fun slugIsFileSafe() {
        assertEquals("saturday-morning-ride", Share.slug("Saturday morning ride"))
        assertEquals("ride", Share.slug("★★"))
    }
}
