package app.roger.dash

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Demo mode: made-up but realistic data so the app can be seen as if the bike were connected.
 * Lives in its own folder (cache/demo) and never touches real rides; deleted when demo mode ends.
 */
object Demo {
    private const val BASE_LAT = 12.9716
    private const val BASE_LON = 77.5946
    private val main = Handler(Looper.getMainLooper())

    /** Fresh demo store: ~5 weeks of rides, fuel fill-ups, a parked spot. */
    fun build(ctx: Context): TripStore {
        val root = File(ctx.cacheDir, "demo")
        root.deleteRecursively()
        val store = TripStore(ctx, root)
        val r = Random(250)
        var odo = 11_640
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        // Rides, oldest first, so the odometer climbs.
        val plan = listOf(
            34 to 8.2, 31 to 46.0, 29 to 12.4, 27 to 168.0, 24 to 9.6, 22 to 23.5, 20 to 61.0,
            17 to 11.2, 15 to 7.9, 13 to 214.0, 10 to 18.3, 8 to 9.1, 6 to 52.7, 4 to 14.6, 2 to 31.2, 1 to 8.8,
        )
        plan.forEachIndexed { i, (daysAgo, km) ->
            val c = Calendar.getInstance().apply {
                timeInMillis = now - daysAgo * day
                set(Calendar.HOUR_OF_DAY, listOf(7, 9, 18, 17, 8, 19, 6)[i % 7]); set(Calendar.MINUTE, r.nextInt(60))
            }
            val avg = if (km > 100) r.nextInt(48, 58) else if (km > 30) r.nextInt(36, 46) else r.nextInt(24, 34)
            val moving = (km / avg * 3600).roundToInt()
            val stopped = if (km > 100) r.nextInt(1800, 4200) else r.nextInt(120, 900)
            val top = (avg + r.nextInt(25, 55)).coerceAtMost(132)
            val pts = route(r, km, moving)
            val speeds = (0 until (moving / 10).coerceIn(12, 400)).map { j ->
                (avg + (top - avg) * 0.6 * sin(j / 9.0 + i) + r.nextInt(-8, 8)).roundToInt().coerceIn(0, top)
            }
            val fuelStart = r.nextInt(3, 7)
            val t = Trip(
                id = c.timeInMillis, name = names.getOrNull(i) ?: Fmt.rideName(c.timeInMillis),
                start = c.timeInMillis, end = c.timeInMillis + (moving + stopped) * 1000L,
                distanceKm = km, movingSec = moving, topKmh = top, startOdo = odo, endOdo = odo + km.roundToInt(),
                fuelStart = fuelStart, fuelEnd = (fuelStart - (km / 70).roundToInt()).coerceAtLeast(1),
                source = "bike", favourite = km > 150, points = pts, speeds = speeds,
            )
            odo += km.roundToInt()
            store.save(t)
        }

        // Fill-ups about every 220–260 km.
        var fOdo = 11_600
        var fDay = 36
        while (fOdo < odo) {
            val litres = (r.nextDouble(7.6, 10.4) * 100).roundToInt() / 100.0
            store.addFuel(FuelEntry(now - fDay * day, litres, (litres * 105.4).roundToInt().toDouble(), fOdo))
            fOdo += r.nextInt(215, 265); fDay -= 7
        }
        store.setParked(TripStore.Parked(BASE_LAT + 0.004, BASE_LON - 0.006, now - 2 * 3_600_000L))
        demoOdo = odo
        return store
    }

    private val names = listOf(
        "Office commute", "Hilltop sunrise", "Office commute", "Weekend getaway", null, "Lake loop",
        "Sunday breakfast run", null, null, "Hill station trip", null, null, "Twisties", null, "Evening loop", null,
    )

    /** A wandering route of about [km], one point every ~250 m. */
    private fun route(r: Random, km: Double, movingSec: Int): List<Pt> {
        val n = (km * 4).roundToInt().coerceIn(10, 600)
        var lat = BASE_LAT + r.nextDouble(-0.03, 0.03)
        var lon = BASE_LON + r.nextDouble(-0.03, 0.03)
        var heading = r.nextDouble(0.0, 2 * PI)
        val step = km / n / 111.0
        return (0 until n).map { i ->
            heading += r.nextDouble(-0.35, 0.35) + if (km < 30 && i > n / 2) 0.06 else 0.0 // short rides loop home
            lat += step * cos(heading); lon += step * sin(heading) / cos(Math.toRadians(lat))
            Pt(lat, lon, movingSec * i / n, r.nextInt(20, 90).toFloat())
        }
    }

    // ───────────── live ride simulation ─────────────

    var demoOdo = 12_480
        private set
    private var tick = 0
    private var trip = 63.4
    private val r = Random(7)

    /** A made-up 15 km route with real-looking turns; the demo rider drives along it. */
    private val legs = listOf(
        Triple(600.0, 0.0, 1 to "Head north on Station Road"),
        Triple(2200.0, 90.0, 10 to "Turn right onto Ring Road"),
        Triple(1500.0, 70.0, 26 to "At the roundabout, take the 2nd exit onto Lake Road"),
        Triple(5000.0, 40.0, 24 to "Keep left to stay on the highway"),
        Triple(3000.0, -50.0, 15 to "Turn left toward the lake"),
        Triple(2500.0, -20.0, 9 to "Slight right onto Hill Road"),
    )

    private fun demoRoute(): Route {
        val shape = ArrayList<LatLon>()
        val man = ArrayList<Maneuver>()
        var lat = BASE_LAT - 0.05; var lon = BASE_LON - 0.04
        shape += LatLon(lat, lon)
        legs.forEachIndexed { i, (len, heading, m) ->
            man += Maneuver(Router.icon(m.first, 2), m.second, shape.lastIndex, m.first)
            val n = (len / 50).roundToInt()
            for (j in 1..n) {
                val h = Math.toRadians(heading + 6 * sin(j / 7.0 + i))
                lat += 50 / 111_000.0 * cos(h); lon += 50 / 111_000.0 * sin(h) / cos(Math.toRadians(lat))
                shape += LatLon(lat, lon)
            }
        }
        man += Maneuver(Frames.Icon.ARRIVE, "You have arrived at Lake View Café", shape.lastIndex, 4)
        val length = legs.sumOf { it.first }
        return Route(shape, man, length, length / 13.0)
    }

    private var route: Route? = null
    private var along = 0.0

    private val step = object : Runnable {
        override fun run() {
            if (!DashHub.demo) return
            tick++
            val speed = (52 + 22 * sin(tick / 14.0) + r.nextInt(-3, 4)).roundToInt().coerceAtLeast(0)
            trip += speed / 3600.0
            if (tick % 60 == 0) demoOdo++
            DashHub.onBike(Frames.BikeData(speed, demoOdo, (trip * 10).roundToInt() / 10.0, 412.7, 4))
            // Move along the route; start over at the end.
            val rt = route ?: return
            along += speed / 3.6
            val i = (along / 50).toInt()
            if (i >= rt.shape.lastIndex - 1) { startRoute(); main.postDelayed(this, 1000); return }
            val p = rt.shape[i]
            DashHub.onLocation(Location("demo").apply {
                latitude = p.lat; longitude = p.lon; this.speed = speed / 3.6f; accuracy = 5f
                bearing = Geo.bearing(p, rt.shape[i + 1]).toFloat()
                time = System.currentTimeMillis()
            })
            main.postDelayed(this, 1000)
        }
    }

    private fun startRoute() {
        val rt = demoRoute()
        route = rt
        along = 450.0
        DashHub.demoNavigate(Place("Lake View Café", "Demo destination", rt.shape.last()), rt)
    }

    fun startLive() {
        tick = 0
        startRoute()
        main.removeCallbacks(step)
        main.post(step)
    }

    fun stopLive() = main.removeCallbacks(step)

    fun wipe(ctx: Context) = File(ctx.cacheDir, "demo").deleteRecursively()
}
