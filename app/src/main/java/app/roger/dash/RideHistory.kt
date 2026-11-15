package app.roger.dash

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** GPX 1.1 export (Strava, Komoot, Google Earth, …). */
object Gpx {
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One GPX file with a track per ride. Rides without a recorded route are left out. */
    fun of(trips: List<Trip>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="RogerDash" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        val first = trips.minOfOrNull { it.start }
        if (first != null) append("  <metadata><time>").append(iso(first)).append("</time></metadata>\n")
        for (t in trips.sortedBy { it.start }) {
            if (t.points.isEmpty()) continue
            append("  <trk>\n    <name>").append(esc(t.name)).append("</name>\n")
            append("    <desc>").append(esc("%.1f km, top %d km/h".format(Locale.US, t.distanceKm, t.topKmh))).append("</desc>\n")
            append("    <type>motorcycling</type>\n    <trkseg>\n")
            for (p in t.points) {
                append("      <trkpt lat=\"").append("%.6f".format(Locale.US, p.lat))
                    .append("\" lon=\"").append("%.6f".format(Locale.US, p.lon)).append("\"><time>")
                    .append(iso(t.start + p.t * 1000L)).append("</time></trkpt>\n")
            }
            append("    </trkseg>\n  </trk>\n")
        }
        append("</gpx>\n")
    }
}

/** Things worth marking on a ride's route. */
object Track {
    data class Stop(val lat: Double, val lon: Double, val atSec: Int, val sec: Int)

    fun dist(a: Pt, b: Pt): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Where the GPS speed was highest (the bike's own top speed may be a little different). */
    fun topPoint(t: Trip): Pt? = t.points.filter { it.kmh > 0 }.maxByOrNull { it.kmh }

    /**
     * Stops of [minSec]+ : the track keeps a point every ~25 m, so a long time between two close
     * points means we were standing (signal, chai, fuel).
     */
    fun stops(t: Trip, minSec: Int = 120): List<Stop> = t.points.zipWithNext().mapNotNull { (a, b) ->
        val dt = b.t - a.t
        if (dt >= minSec && dist(a, b) < 80) Stop(a.lat, a.lon, a.t, dt) else null
    }
}

/** One calendar month: riding plus fuel from the fill-up log. */
data class MonthReport(
    val year: Int,
    val month: Int,              // Calendar.JANUARY..DECEMBER
    val rides: List<Trip>,
    val km: Double,
    val movingSec: Int,
    val longest: Trip?,
    val fuelLitres: Double,
    val fuelCost: Double,
    val kmPerL: Double?,
) {
    val title: String get() = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        .format(Calendar.getInstance().apply { clear(); set(year, month, 1) }.time)

    companion object {
        fun range(year: Int, month: Int): Pair<Long, Long> {
            val c = Calendar.getInstance().apply { clear(); set(year, month, 1) }
            val from = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            return from to c.timeInMillis
        }

        fun of(year: Int, month: Int, trips: List<Trip>, fuel: List<FuelEntry>): MonthReport {
            val (from, to) = range(year, month)
            val rides = trips.filter { it.start in from until to }.sortedByDescending { it.start }
            val fills = fuel.filter { it.time in from until to }
            // Mileage: for each fill-up this month, distance since the fill-up before it ÷ litres put in.
            var km = 0.0; var litres = 0.0
            for (f in fills) {
                val sorted = fuel.filter { it.vehicle == f.vehicle }.sortedBy { it.odo } // each vehicle has its own odometer
                val i = sorted.indexOf(f)
                if (i > 0 && f.litres > 0 && f.odo > sorted[i - 1].odo) { km += f.odo - sorted[i - 1].odo; litres += f.litres }
            }
            return MonthReport(
                year, month, rides,
                rides.sumOf { it.distanceKm }, rides.sumOf { it.movingSec },
                rides.maxByOrNull { it.distanceKm },
                fills.sumOf { it.litres }, fills.sumOf { it.cost },
                if (litres > 0) km / litres else null,
            )
        }
    }
}
