package app.roger.dash

import android.location.Location
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Builds one ride from the bike's own data (speed, odometer, trip meter, fuel; most accurate)
 * and the phone's GPS (route shape; also distance/speed when the bike isn't connected).
 */
class RideRecorder(alreadyMs: Long = 0, val vehicle: String = Vehicle.MAIN) {
    private val start: Long = System.currentTimeMillis() - alreadyMs
    private val t0 = SystemClock.elapsedRealtime() - alreadyMs

    private var startOdo: Int? = null
    private var lastOdo: Int? = null
    private var startTrip: Double? = null
    private var lastTrip: Double? = null
    private var tripDelta = 0.0         // trip-meter km, robust to the rider resetting it mid-ride
    private var fuelStart: Int? = null
    private var fuelEnd: Int? = null

    private var lastFix: Location? = null
    private var gpsKm = 0.0
    private val points = ArrayList<Pt>()

    private var lastSampleAt = 0L
    private var movingMs = 0L
    private var top = 0
    private val speeds = ArrayList<Int>()
    private var bucketMax = 0
    private var bucketStart = 0L
    private var bikeSpeedAt = 0L

    /** Latest speed, for the live screen. */
    var speedKmh = 0
        private set

    val elapsedSec: Int get() = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
    val movingSec: Int get() = (movingMs / 1000).toInt()
    val topKmh: Int get() = top
    val hasBike: Boolean get() = startOdo != null || startTrip != null

    /** Best distance so far: the bike's trip meter, else its odometer, else GPS. */
    val distanceKm: Double
        get() = when {
            startTrip != null && tripDelta > 0 -> tripDelta
            startOdo != null && lastOdo != null && lastOdo!! > startOdo!! -> (lastOdo!! - startOdo!!).toDouble()
            else -> gpsKm
        }

    @Synchronized fun onBike(d: Frames.BikeData) {
        d.odoKm?.let { if (startOdo == null) startOdo = it; lastOdo = it }
        d.tripAKm?.let { t ->
            if (startTrip == null) startTrip = t
            lastTrip?.let { prev -> if (t >= prev) tripDelta += t - prev } // a reset shows as a drop
            lastTrip = t
        }
        d.fuelBars?.let { if (fuelStart == null) fuelStart = it; fuelEnd = it }
        d.speedKmh?.let { bikeSpeedAt = SystemClock.elapsedRealtime(); sample(it) }
    }

    @Synchronized fun onLocation(l: Location) {
        val prev = lastFix
        if (l.hasAccuracy() && l.accuracy > 30) return
        if (prev != null) {
            val d = prev.distanceTo(l)
            if (d < 3) return // standing still: skip GPS jitter
            gpsKm += d / 1000.0
        }
        lastFix = l
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        points += Pt(l.latitude, l.longitude, elapsedSec, kmh)
        // GPS speed only when the bike isn't telling us its speed.
        if (SystemClock.elapsedRealtime() - bikeSpeedAt > 3000) sample(kmh.roundToInt())
    }

    private fun sample(kmh: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAt > 0) {
            val dt = (now - lastSampleAt).coerceAtMost(5000)
            if (kmh >= 5) movingMs += dt
        }
        lastSampleAt = now
        speedKmh = kmh
        if (kmh > top && kmh < 250) top = kmh
        if (openLeg != null && kmh > legTop && kmh < 250) legTop = kmh
        if (bucketStart == 0L) bucketStart = now
        bucketMax = maxOf(bucketMax, kmh)
        if (now - bucketStart >= 10_000) { speeds += bucketMax; bucketMax = 0; bucketStart = now }
    }

    val lastPosition: Location? get() = lastFix

    // ───────────── sub-trips (navigations) ─────────────

    private class OpenLeg(val to: String, val from: String?, val start: Long, val startSec: Int, val startKm: Double, val startMovingMs: Long)
    private var openLeg: OpenLeg? = null
    private var legTop = 0
    private val legs = ArrayList<Leg>()

    /** Navigation to [to] started: a new sub-trip (ends any open one first). */
    @Synchronized fun startLeg(to: String) {
        endLeg("cancelled")
        // Continuing from the last place we arrived at (within 30 min) → "A → B".
        val prev = legs.lastOrNull()?.takeIf { it.arrived && System.currentTimeMillis() - it.end < 30 * 60_000L }
        openLeg = OpenLeg(to, prev?.to, System.currentTimeMillis(), elapsedSec, distanceKm, movingMs)
        legTop = 0
    }

    /** The open sub-trip ends here: "arrived", "cancelled" (navigation stopped), or "ride ended". */
    @Synchronized fun endLeg(reason: String) {
        val o = openLeg ?: return
        openLeg = null
        val km = (distanceKm - o.startKm).coerceAtLeast(0.0)
        if (km < 0.05 && reason != "arrived") return // started and stopped on the spot: not a trip
        legs += Leg(o.to, o.from, o.start, System.currentTimeMillis(), o.startSec, elapsedSec,
            (km * 10).roundToInt() / 10.0, ((movingMs - o.startMovingMs) / 1000).toInt(), legTop, reason)
    }

    val legCount: Int get() = legs.size + if (openLeg != null) 1 else 0

    /** Demo mode: pretend the ride has been going for a while. */
    @Synchronized fun seed(km: Double, movingSec: Int, topKmh: Int) {
        tripDelta += km
        if (startTrip == null) gpsKm += km
        movingMs += movingSec * 1000L
        top = maxOf(top, topKmh)
    }

    /** The finished ride, or null if it's too short to keep: under 300 m (e.g. parked with the ignition on). */
    @Synchronized fun finish(): Trip? {
        endLeg("ride ended")
        val km = distanceKm
        if (km < 0.3) return null
        return Trip(
            id = start, name = Fmt.rideName(start), start = start, end = System.currentTimeMillis(),
            distanceKm = (km * 10).roundToInt() / 10.0, movingSec = movingSec, topKmh = top,
            startOdo = startOdo, endOdo = lastOdo, fuelStart = fuelStart, fuelEnd = fuelEnd,
            source = if (hasBike) "bike" else "gps",
            points = thin(points), speeds = speeds.toList(), legs = legs.toList(), vehicle = vehicle,
        )
    }

    /** Keep the track small: a point every ~25 m. */
    private fun thin(p: List<Pt>): List<Pt> {
        if (p.size < 3) return p
        val out = ArrayList<Pt>()
        var last = p.first()
        out += last
        val r = FloatArray(1)
        for (x in p.drop(1)) {
            Location.distanceBetween(last.lat, last.lon, x.lat, x.lon, r)
            if (r[0] >= 25) { out += x; last = x }
        }
        if (out.last() != p.last()) out += p.last()
        return out
    }
}
