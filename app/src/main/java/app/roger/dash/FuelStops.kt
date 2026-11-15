package app.roger.dash

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A petrol pump near the route: how far along the route it is, and how far off it. */
data class FuelStop(val place: Place, val alongM: Double, val offM: Double)

/**
 * Petrol pumps along a route, from OpenStreetMap data (free, no key). Two sources run together:
 *  - Photon (the place search we already use; fast and reliable): brand searches near points on the route;
 *  - Overpass (complete, including unnamed pumps, but the free server is often overloaded; 504 / timeouts
 *    seen 2026-11-14), so it only adds results when it answers in time.
 * Only points on the route are sent, nothing about the rider.
 */
object FuelStops {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val NEAR_M = 800.0
    private const val FAR_M = 5000.0 // when nothing is on the way, offer pumps up to this far off
    private val TERMS = listOf("petrol pump", "Indian Oil", "HP petrol", "Bharat Petroleum", "fuel")

    private data class Found(val name: String, val detail: String, val pos: LatLon)

    /** Blocking. Pumps along [shape] from [fromM] metres along it, in route order. Google first (rider's key), else OpenStreetMap. */
    fun along(shape: List<LatLon>, fromM: Double = 0.0, maxResults: Int = 15): List<FuelStop> {
        if (shape.size < 2) return emptyList()
        val cum = DoubleArray(shape.size).also { c -> for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.dist(shape[i - 1], shape[i]) }
        val key = DashHub.googleKey
        if (key.isNotBlank()) {
            runCatching { google(key, shape, cum, fromM) }
                .onSuccess { GoogleAuth.worked(); if (it.isNotEmpty()) return it.take(maxResults) else DashHub.onLog("Fuel search: Google found none; trying OpenStreetMap") }
                .onFailure { GoogleAuth.failed(it); DashHub.onLog("Fuel search: Google failed (${it.message}); trying OpenStreetMap") }
        }
        return openStreetMap(shape, cum, fromM, maxResults)
    }

    /**
     * Google Places "search along route": petrol pumps along the road ahead, the ones needing the smallest
     * detour first. Two searches merged: the next ~60 km (what you need soon) and the whole rest of the route.
     */
    private fun google(key: String, shape: List<LatLon>, cum: DoubleArray, fromM: Double): List<FuelStop> {
        fun part(toM: Double): List<LatLon> {
            // Thin to a point every ~150 m: plenty for Google, keeps the request small on long trips.
            val out = ArrayList<LatLon>(); var last = -1e9
            for (i in shape.indices) if (cum[i] >= fromM && cum[i] <= toM && cum[i] - last >= 150) { out += shape[i]; last = cum[i] }
            out += shape[cum.indexOfFirst { it >= toM }.let { if (it < 0) shape.lastIndex else it }]
            return out
        }
        val total = cum.last()
        val near = part(fromM + 60_000)
        val parts = if (total - fromM > 70_000) listOf(near, part(total)) else listOf(near)
        val found = ArrayList<FuelStop>()
        for (pts in parts) {
            if (pts.size < 2) continue
            val body = JSONObject()
                .put("textQuery", "petrol pump")
                .put("includedType", "gas_station")
                .put("maxResultCount", 20)
                .put("languageCode", "en")
                .put("searchAlongRouteParameters", JSONObject().put("polyline", JSONObject().put("encodedPolyline", Geo.encode(pts))))
            val res = JSONObject(Http.post("https://places.googleapis.com/v1/places:searchText", body.toString(),
                GoogleAuth.headers(DashHub.app, key) + ("X-Goog-FieldMask" to "places.displayName,places.formattedAddress,places.location"),
                timeoutMs = 15_000))
            val ps = res.optJSONArray("places") ?: continue
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                val loc = p.optJSONObject("location") ?: continue
                val pos = LatLon(loc.getDouble("latitude"), loc.getDouble("longitude"))
                var best = Double.MAX_VALUE; var at = 0.0
                for (s in 0 until shape.size - 1) {
                    val (d, t) = Geo.toSegment(pos, shape[s], shape[s + 1])
                    if (d < best) { best = d; at = cum[s] + (cum[s + 1] - cum[s]) * t }
                }
                if (at < fromM || best > FAR_M) continue
                val name = p.optJSONObject("displayName")?.optString("text").orEmpty().ifBlank { "Petrol pump" }
                found += FuelStop(Place(name, p.optString("formattedAddress"), pos), at, best)
            }
        }
        return found.fold(ArrayList<FuelStop>()) { acc, s -> if (acc.none { Geo.dist(it.place.pos!!, s.place.pos!!) < 80 }) acc += s; acc }
            .sortedBy { it.alongM }
    }

    private fun openStreetMap(shape: List<LatLon>, cum: DoubleArray, fromM: Double, maxResults: Int): List<FuelStop> {
        fun pointAt(m: Double): LatLon { val i = cum.indexOfFirst { it >= m }.let { if (it < 0) shape.lastIndex else it }; return shape[i] }
        val total = cum.last()

        val pool = Executors.newFixedThreadPool(5)
        try {
            // Overpass: the line thinned to a point every ~800 m.
            val line = ArrayList<LatLon>()
            var next = fromM
            for (i in shape.indices) if (cum[i] >= next) { line += shape[i]; next = cum[i] + 800 }
            if (line.size < 2) line += shape.last()
            val overpass: Future<List<Found>> = pool.submit(Callable { overpass(line.take(300)) })

            // Photon: up to 5 points spread over the rest of the route (every ~3 km on short routes).
            val left = (total - fromM).coerceAtLeast(0.0)
            val n = (left / 3000).toInt().coerceIn(1, 5)
            val samples = (0 until n).map { pointAt(fromM + left * (it + 0.5) / n) }
            val photon = samples.flatMap { p -> TERMS.map { t -> pool.submit(Callable { photon(t, p) }) } }

            val found = ArrayList<Found>()
            photon.forEach { f -> runCatching { found += f.get(12, TimeUnit.SECONDS) } }
            runCatching { found += overpass.get(4, TimeUnit.SECONDS) } // whatever Overpass managed meanwhile
                .onFailure { DashHub.onLog("Fuel search: Overpass didn't answer (${it.javaClass.simpleName}), using place search only") }

            val all = found.mapNotNull { f ->
                var best = Double.MAX_VALUE; var at = 0.0
                for (s in 0 until shape.size - 1) {
                    val (d, t) = Geo.toSegment(f.pos, shape[s], shape[s + 1])
                    if (d < best) { best = d; at = cum[s] + (cum[s + 1] - cum[s]) * t }
                }
                if (best > FAR_M || at < fromM) null else FuelStop(Place(f.name, f.detail, f.pos), at, best)
            }
                .sortedBy { it.offM }
                .fold(ArrayList<FuelStop>()) { acc, s -> if (acc.none { Geo.dist(it.place.pos!!, s.place.pos!!) < 80 }) acc += s; acc }
            // On the way if possible; otherwise the nearest few, with how far off the route they are.
            val onWay = all.filter { it.offM <= NEAR_M }
            return if (onWay.isNotEmpty()) onWay.sortedBy { it.alongM }.take(maxResults) else all.take(5)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun photon(term: String, near: LatLon): List<Found> {
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(term, "UTF-8")}&osm_tag=amenity:fuel" +
            "&lat=${near.lat}&lon=${near.lon}&location_bias_scale=0.05&zoom=15&limit=10&lang=en"
        val fs = JSONObject(Http.get(url, timeoutMs = 10_000)).getJSONArray("features")
        return (0 until fs.length()).map { i ->
            val f = fs.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            Found(clean(p.optString("name")).ifBlank { "Petrol pump" },
                listOf(p.optString("street"), p.optString("district"), p.optString("city")).filter { it.isNotBlank() }.distinct().joinToString(", "),
                LatLon(c.getDouble(1), c.getDouble(0)))
        }
    }

    private fun overpass(line: List<LatLon>): List<Found> {
        val l = line.joinToString(",") { "%.5f,%.5f".format(Locale.US, it.lat, it.lon) }
        val q = "[out:json][timeout:20];(node(around:${NEAR_M.toInt()},$l)[amenity=fuel];way(around:${NEAR_M.toInt()},$l)[amenity=fuel];);out center 60;"
        val c = java.net.URL(OVERPASS).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 6_000; c.readTimeout = 14_000
        c.setRequestProperty("User-Agent", "RogerDash/1.0 (open-source motorcycle app)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = try {
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
        val els = JSONObject(body).getJSONArray("elements")
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val c2 = if (e.has("center")) e.getJSONObject("center") else e
            if (!c2.has("lat")) return@mapNotNull null
            val tags = e.optJSONObject("tags")
            val brand = tags?.optString("brand").orEmpty()
            Found(clean(tags?.optString("name").orEmpty()).ifBlank { brand.ifBlank { "Petrol pump" } },
                listOf(brand, tags?.optString("addr:street").orEmpty()).filter { it.isNotBlank() }.joinToString(", "),
                LatLon(c2.getDouble("lat"), c2.getDouble("lon")))
        }
    }

    /** Names sometimes carry a second script after " - "; keep the readable English part. */
    private fun clean(name: String): String =
        name.split(" - ").firstOrNull { part -> part.any { it in 'A'..'z' } && part.all { it.code < 0x0900 } }?.trim() ?: name.trim()
}
