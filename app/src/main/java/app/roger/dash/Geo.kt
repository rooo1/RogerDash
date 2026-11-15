package app.roger.dash

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_000.0

    /** Metres between two points. */
    fun dist(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Closest point to [p] on segment a–b: returns (distance in metres, fraction 0..1 along the segment).
     * Uses a local flat projection, which is accurate at these short distances.
     */
    fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = a.lon * k; val ay = a.lat
        val bx = b.lon * k; val by = b.lat
        val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val q = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return dist(p, q) to t
    }

    /** Compass bearing a → b, degrees 0..360 (0 = north). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val φ1 = Math.toRadians(a.lat); val φ2 = Math.toRadians(b.lat)
        val dλ = Math.toRadians(b.lon - a.lon)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The point [m] metres from [p] towards [bearingDeg] (flat-earth; fine up to ~100 km). */
    fun move(p: LatLon, bearingDeg: Double, m: Double): LatLon {
        val b = Math.toRadians(bearingDeg)
        val dLat = m * cos(b) / 111_320.0
        val dLon = m * sin(b) / (111_320.0 * cos(Math.toRadians(p.lat)))
        return LatLon(p.lat + dLat, p.lon + dLon)
    }

    /** Smallest difference between two bearings, 0..180. */
    fun angle(a: Double, b: Double): Double { val d = Math.abs(a - b) % 360; return if (d > 180) 360 - d else d }

    /** Encode points as a Google polyline (precision 5). */
    fun encode(points: List<LatLon>, precision: Int = 5): String {
        val f = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1F).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = Math.round(p.lat * f); val lon = Math.round(p.lon * f)
            put(lat - pLat); put(lon - pLon); pLat = lat; pLon = lon
        }
        return sb.toString()
    }

    /** Google/Valhalla encoded polyline (Valhalla uses precision 6). */
    fun decode(s: String, precision: Int = 6): List<LatLon> {
        val f = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            for (pass in 0..1) {
                var shift = 0; var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (pass == 0) lat += d else lon += d
            }
            out += LatLon(lat / f, lon / f)
        }
        return out
    }
}
