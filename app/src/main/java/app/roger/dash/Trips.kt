package app.roger.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A GPS point of a ride: position, seconds since start, speed. */
data class Pt(val lat: Double, val lon: Double, val t: Int, val kmh: Float)

/**
 * A sub-trip inside a ride: one navigation the rider started, from start to arrival (or to where they
 * cancelled it). [startSec]/[endSec] are seconds since the ride's start, for cutting its piece of the track.
 */
data class Leg(
    val to: String,
    val from: String?,          // previous destination when it continues from there, else null
    val start: Long,
    val end: Long,
    val startSec: Int,
    val endSec: Int,
    val distanceKm: Double,
    val movingSec: Int,
    val topKmh: Int,
    val ended: String,          // "arrived", "cancelled", "ride ended"
) {
    val totalSec: Int get() = ((end - start) / 1000).toInt()
    val avgKmh: Double get() = if (movingSec > 30) distanceKm / (movingSec / 3600.0) else 0.0
    val arrived: Boolean get() = ended == "arrived"

    fun toJson() = JSONObject().apply {
        put("to", to); from?.let { put("from", it) }; put("start", start); put("end", end)
        put("startSec", startSec); put("endSec", endSec); put("distanceKm", distanceKm)
        put("movingSec", movingSec); put("topKmh", topKmh); put("ended", ended)
    }

    /** This leg's piece of the ride's GPS track. */
    fun track(points: List<Pt>) = points.filter { it.t in startSec..endSec }

    companion object {
        fun fromJson(o: JSONObject) = Leg(
            o.getString("to"), if (o.has("from")) o.getString("from") else null, o.getLong("start"), o.getLong("end"),
            o.getInt("startSec"), o.getInt("endSec"), o.getDouble("distanceKm"), o.getInt("movingSec"),
            o.getInt("topKmh"), o.optString("ended", "arrived"),
        )
    }
}

data class Trip(
    val id: Long,              // start time, ms
    var name: String,
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    val movingSec: Int,        // time actually riding (speed ≥ 5 km/h)
    val topKmh: Int,
    val startOdo: Int?,
    val endOdo: Int?,
    val fuelStart: Int?,       // fuel bars on the dash (0–6)
    val fuelEnd: Int?,
    val source: String,        // "bike" (odometer/trip meter) or "gps"
    var favourite: Boolean = false,
    val points: List<Pt> = emptyList(),
    val speeds: List<Int> = emptyList(), // km/h every 10 s
    val legs: List<Leg> = emptyList(),   // navigations started during this ride (sub-trips)
    val vehicle: String = Vehicle.MAIN,  // which bike / scooter ([Vehicle.id])
) {
    val totalSec: Int get() = ((end - start) / 1000).toInt()
    val avgKmh: Double get() = if (movingSec > 30) distanceKm / (movingSec / 3600.0) else 0.0
    val stoppedSec: Int get() = (totalSec - movingSec).coerceAtLeast(0)

    fun toJson(withTrack: Boolean = true) = JSONObject().apply {
        put("id", id); put("name", name); put("start", start); put("end", end)
        put("distanceKm", distanceKm); put("movingSec", movingSec); put("topKmh", topKmh)
        startOdo?.let { put("startOdo", it) }; endOdo?.let { put("endOdo", it) }
        fuelStart?.let { put("fuelStart", it) }; fuelEnd?.let { put("fuelEnd", it) }
        put("source", source); put("favourite", favourite)
        if (legs.isNotEmpty()) put("legs", JSONArray(legs.map { it.toJson() }))
        put("vehicle", vehicle)
        if (withTrack) {
            put("points", JSONArray().apply {
                points.forEach { put(JSONArray().put(it.lat).put(it.lon).put(it.t).put(it.kmh.toDouble())) }
            })
            put("speeds", JSONArray(speeds))
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val pts = o.optJSONArray("points")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONArray(i)
                    Pt(p.getDouble(0), p.getDouble(1), p.getInt(2), p.getDouble(3).toFloat())
                }
            } ?: emptyList()
            val sp = o.optJSONArray("speeds")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
            fun optInt(k: String) = if (o.has(k)) o.getInt(k) else null
            return Trip(
                o.getLong("id"), o.getString("name"), o.getLong("start"), o.getLong("end"),
                o.getDouble("distanceKm"), o.getInt("movingSec"), o.getInt("topKmh"),
                optInt("startOdo"), optInt("endOdo"), optInt("fuelStart"), optInt("fuelEnd"),
                o.optString("source", "gps"), o.optBoolean("favourite"), pts, sp,
                o.optJSONArray("legs")?.let { a -> (0 until a.length()).map { Leg.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                o.optString("vehicle", Vehicle.MAIN),
            )
        }
    }
}

/** A fuel fill-up the rider logs. */
data class FuelEntry(val time: Long, val litres: Double, val cost: Double, val odo: Int, val full: Boolean = true, val vehicle: String = Vehicle.MAIN) {
    fun toJson() = JSONObject().put("time", time).put("litres", litres).put("cost", cost).put("odo", odo).put("full", full).put("vehicle", vehicle)
    companion object {
        fun fromJson(o: JSONObject) = FuelEntry(o.getLong("time"), o.getDouble("litres"), o.getDouble("cost"), o.getInt("odo"),
            o.optBoolean("full", true), o.optString("vehicle", Vehicle.MAIN))
    }
}

/** Trips as one JSON file each (with track); fuel log and parked spot as small JSON files. */
class TripStore(ctx: Context, root: File = ctx.filesDir) {
    private val dir = File(root, "trips").apply { mkdirs() }
    private val fuelFile = File(root, "fuel.json")
    private val parkedFile = File(root, "parked.json")
    private var cache: MutableList<Trip>? = null

    @Synchronized fun trips(): List<Trip> {
        cache?.let { return it }
        val list = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull {
            runCatching { Trip.fromJson(JSONObject(it.readText())) }.getOrNull()
        }.sortedByDescending { it.start }.toMutableList()
        cache = list
        return list
    }

    @Synchronized fun save(t: Trip) {
        File(dir, "${t.id}.json").writeText(t.toJson().toString())
        val c = trips() as MutableList<Trip>
        c.removeAll { it.id == t.id }
        c.add(t)
        c.sortByDescending { it.start }
    }

    @Synchronized fun delete(t: Trip) {
        File(dir, "${t.id}.json").delete()
        (trips() as MutableList<Trip>).removeAll { it.id == t.id }
    }

    fun fuel(): List<FuelEntry> = runCatching {
        val a = JSONArray(fuelFile.readText())
        (0 until a.length()).map { FuelEntry.fromJson(a.getJSONObject(it)) }.sortedBy { it.odo }
    }.getOrDefault(emptyList())

    /** One vehicle's fill-ups. */
    fun fuel(vehicle: String): List<FuelEntry> = fuel().filter { it.vehicle == vehicle }

    /** One vehicle's rides. */
    fun trips(vehicle: String): List<Trip> = trips().filter { it.vehicle == vehicle }

    fun addFuel(e: FuelEntry) = writeFuel(fuel() + e)
    fun deleteFuel(e: FuelEntry) = writeFuel(fuel() - e)
    private fun writeFuel(l: List<FuelEntry>) = fuelFile.writeText(JSONArray(l.map { it.toJson() }).toString())

    data class Parked(val lat: Double, val lon: Double, val time: Long)

    /** Where each vehicle was last parked (the Suzuki's in parked.json, as before vehicles). */
    private fun parkedFile(vehicle: String) = if (vehicle == Vehicle.MAIN) parkedFile else File(parkedFile.parentFile, "parked_$vehicle.json")

    fun parked(vehicle: String = Vehicle.MAIN): Parked? = runCatching {
        val o = JSONObject(parkedFile(vehicle).readText())
        Parked(o.getDouble("lat"), o.getDouble("lon"), o.getLong("time"))
    }.getOrNull()

    fun setParked(p: Parked, vehicle: String = Vehicle.MAIN) =
        parkedFile(vehicle).writeText(JSONObject().put("lat", p.lat).put("lon", p.lon).put("time", p.time).toString())
}

/** Totals over a period. */
data class Stats(val rides: Int, val km: Double, val movingSec: Int, val topKmh: Int, val longestKm: Double) {
    val avgKmh get() = if (movingSec > 60) km / (movingSec / 3600.0) else 0.0

    companion object {
        fun of(trips: List<Trip>) = Stats(
            trips.size, trips.sumOf { it.distanceKm }, trips.sumOf { it.movingSec },
            trips.maxOfOrNull { it.topKmh } ?: 0, trips.maxOfOrNull { it.distanceKm } ?: 0.0,
        )

        fun since(trips: List<Trip>, period: Int): Stats {
            if (period == ALL) return of(trips)
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (period == WEEK) c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek) else c.set(Calendar.DAY_OF_MONTH, 1)
            return of(trips.filter { it.start >= c.timeInMillis })
        }

        const val WEEK = 0
        const val MONTH = 1
        const val ALL = 2
    }
}

/**
 * Fuel economy by the full-tank method: between two full-tank fill-ups, the km ridden ÷ the litres put in
 * (top-ups in between count towards the litres). Example: full at 4,500 km, full again at 4,650 km with
 * 4.5 L → 150 / 4.5 = 33.3 km/l. Measured, unlike the dash's average.
 * [intervals]: how many full-to-full stretches were measured; [kmMeasured]: their total km.
 */
data class Economy(
    val kmPerL: Double?, val costPerKm: Double?, val litres: Double, val cost: Double,
    val intervals: Int = 0, val kmMeasured: Double = 0.0,
) {
    companion object {
        fun of(log: List<FuelEntry>): Economy {
            val l = log.sortedBy { it.odo }
            var km = 0.0; var litres = 0.0; var cost = 0.0; var n = 0
            var lastFull = -1
            for (i in l.indices) {
                if (!l[i].full) continue
                if (lastFull >= 0) {
                    val d = (l[i].odo - l[lastFull].odo).toDouble()
                    val part = l.subList(lastFull + 1, i + 1)
                    val lt = part.sumOf { it.litres }
                    if (d > 0 && lt > 0) { km += d; litres += lt; cost += part.sumOf { it.cost }; n++ }
                }
                lastFull = i
            }
            return Economy(
                if (litres > 0) km / litres else null,
                if (km > 0 && cost > 0) cost / km else null,
                l.sumOf { it.litres }, l.sumOf { it.cost }, n, km,
            )
        }

        /** km/l measured at the fill-up at index [i] (sorted by odometer): only for a full tank after an earlier full tank. */
        fun at(log: List<FuelEntry>, i: Int): Double? {
            val l = log.sortedBy { it.odo }
            if (i <= 0 || i >= l.size || !l[i].full) return null
            val j = (i - 1 downTo 0).firstOrNull { l[it].full } ?: return null
            val lt = l.subList(j + 1, i + 1).sumOf { it.litres }
            val d = l[i].odo - l[j].odo
            return if (lt > 0 && d > 0) d / lt else null
        }

        /** Price of the latest fill-up with a cost, ₹ per litre. */
        fun pricePerL(log: List<FuelEntry>): Double? =
            log.sortedBy { it.time }.lastOrNull { it.cost > 0 && it.litres > 0 }?.let { it.cost / it.litres }

        /** Fuel spending per calendar month, the last [months] months (oldest first), as (month start, ₹). */
        fun monthly(log: List<FuelEntry>, months: Int = 6, now: Long = System.currentTimeMillis()): List<Pair<Long, Double>> {
            val c = Calendar.getInstance().apply {
                timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, -(months - 1))
            }
            return (0 until months).map {
                val from = c.timeInMillis
                c.add(Calendar.MONTH, 1)
                from to log.filter { e -> e.time >= from && e.time < c.timeInMillis }.sumOf { e -> e.cost }
            }
        }
    }
}

object Fmt {
    fun km(v: Double) = when {
        v < 10 -> "%.1f km".format(Locale.US, v)
        else -> "%,d km".format(Locale.US, v.roundToInt())
    }

    fun dur(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "$h h %02d min".format(m) else "$m min"
    }

    /** 12.0 → "12", 5.3 → "5.3". */
    fun num(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(Locale.US, d)

    fun clock(sec: Int) = "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    fun day(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    fun dayTime(ms: Long) = "${day(ms)} · ${time(ms)}"

    fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }

    /** "Saturday evening ride". */
    fun rideName(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val part = when (c.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night"
        }
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms)) + " $part ride"
    }
}
