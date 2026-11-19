package app.roger.dash

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A search result. [pos] is null for Google results until [Places.resolve] fetches it
 * (Google returns coordinates only for the place the rider picks).
 */
data class Place(
    val name: String,
    val detail: String,
    val pos: LatLon?,
    val googleId: String? = null,
    val distM: Double? = null,
    /** From nearby search (Google): star rating and whether it's open now. */
    val rating: Double? = null,
    val openNow: Boolean? = null,
)

/** Quick searches on the Navigate map, like Google Maps' chips. */
enum class Category(val label: String, val title: String, val googleType: String, val osmTag: String, val osmTerm: String) {
    PETROL("⛽  Petrol", "Petrol pumps nearby", "gas_station", "amenity:fuel", "petrol pump"),
    FOOD("🍴  Restaurants", "Restaurants nearby", "restaurant", "amenity:restaurant", "restaurant"),
    CAFE("☕  Cafés", "Cafés nearby", "cafe", "amenity:cafe", "cafe"),
    ATM("🏧  ATMs", "ATMs nearby", "atm", "amenity:atm", "atm"),
    HOTEL("🏨  Hotels", "Hotels nearby", "lodging", "tourism:hotel", "hotel"),
}

/**
 * Place search as you type.
 *  - With a Google Places API key: Google's search (up-to-date shops, malls, addresses).
 *  - Without: Photon (OpenStreetMap data; free, no key, but misses many newer places in India).
 * Results come nearest first.
 */
object Places {
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    /** Google bills a typing session + the chosen place as one; a new token per search session. */
    @Volatile private var session = UUID.randomUUID().toString()

    val usingGoogle: Boolean get() = DashHub.googleKey.isNotBlank()

    /** Searches in the background; [done] gets the newest query's results only (null = failed). */
    fun search(query: String, near: LatLon?, done: (List<Place>?) -> Unit) {
        val gen = ++generation
        val key = DashHub.googleKey
        pool.execute {
            if (gen != generation) return@execute // a newer keystroke superseded this one
            // Google first with the rider's key; if Google refuses it (or is down), OpenStreetMap instead of nothing.
            val r = runCatching {
                if (key.isBlank()) photon(query, near)
                else runCatching { google(query, near, key) }
                    .onSuccess { GoogleAuth.worked() }
                    .onFailure { GoogleAuth.failed(it); DashHub.onLog("Search (Google) failed: ${it.message}; using OpenStreetMap") }
                    .getOrNull() ?: photon(query, near)
            }
            r.exceptionOrNull()?.let { DashHub.onLog("Search failed: ${it.message}") }
            if (gen == generation) done(r.getOrNull())
        }
    }

    /** Nearest places of a [Category] around [near] (Google nearby search; OpenStreetMap without a key). */
    fun nearby(c: Category, near: LatLon, done: (List<Place>?) -> Unit) {
        val key = DashHub.googleKey
        pool.execute {
            val r = runCatching {
                if (key.isNotBlank()) runCatching { googleNearby(c, near, key) }
                    .onSuccess { GoogleAuth.worked() }
                    .onFailure { GoogleAuth.failed(it); DashHub.onLog("Nearby search (Google) failed: ${it.message}") }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: photonNearby(c, near)
                else photonNearby(c, near)
            }
            r.exceptionOrNull()?.let { DashHub.onLog("Nearby search failed: ${it.message}") }
            done(r.getOrNull())
        }
    }

    private fun googleNearby(c: Category, near: LatLon, key: String): List<Place> {
        val body = JSONObject()
            .put("includedTypes", JSONArray().put(c.googleType))
            .put("maxResultCount", 20)
            .put("rankPreference", "DISTANCE")
            .put("languageCode", "en")
            .put("locationRestriction", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", near.lat).put("longitude", near.lon)).put("radius", 8000.0)))
        val o = JSONObject(Http.post("https://places.googleapis.com/v1/places:searchNearby", body.toString(),
            GoogleAuth.headers(DashHub.app, key) + ("X-Goog-FieldMask" to
                "places.displayName,places.formattedAddress,places.location,places.rating,places.currentOpeningHours.openNow")))
        val ps = o.optJSONArray("places") ?: return emptyList()
        // The bike runs on petrol: leave out CNG-only stations.
        fun cngOnly(name: String) = c == Category.PETROL && name.contains("CNG", ignoreCase = true) &&
            !name.contains("petrol", ignoreCase = true) && !name.contains("pump", ignoreCase = true)
        return (0 until ps.length()).mapNotNull { i ->
            val p = ps.getJSONObject(i)
            val l = p.optJSONObject("location") ?: return@mapNotNull null
            val pos = LatLon(l.getDouble("latitude"), l.getDouble("longitude"))
            val name = p.optJSONObject("displayName")?.optString("text").orEmpty()
            if (cngOnly(name)) return@mapNotNull null
            Place(
                name = name.ifBlank { c.title },
                detail = p.optString("formattedAddress"),
                pos = pos,
                distM = Geo.dist(near, pos),
                rating = if (p.has("rating")) p.getDouble("rating") else null,
                openNow = p.optJSONObject("currentOpeningHours")?.let { if (it.has("openNow")) it.getBoolean("openNow") else null },
            )
        }
    }

    private fun photonNearby(c: Category, near: LatLon): List<Place> {
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(c.osmTerm, "UTF-8")}&osm_tag=${c.osmTag}" +
            "&lat=${near.lat}&lon=${near.lon}&location_bias_scale=0.05&zoom=15&limit=20&lang=en"
        val fs = JSONObject(Http.get(url)).getJSONArray("features")
        return (0 until fs.length()).map { i ->
            val f = fs.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val co = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pos = LatLon(co.getDouble(1), co.getDouble(0))
            Place(p.optString("name").ifBlank { c.title }, listOf(p.optString("street"), p.optString("city")).filter { it.isNotBlank() }.joinToString(", "),
                pos, distM = Geo.dist(near, pos))
        }.filter { (it.distM ?: 0.0) < 15_000 }.sortedBy { it.distM }
    }

    /** Coordinates for a picked place (Google results), on a background thread. */
    fun resolve(p: Place, done: (Place?) -> Unit) {
        if (p.pos != null || p.googleId == null) { done(p); return }
        val key = DashHub.googleKey
        pool.execute {
            val r = runCatching {
                val url = "https://places.googleapis.com/v1/places/${p.googleId}?sessionToken=$session"
                val o = JSONObject(Http.get(url, headers = GoogleAuth.headers(DashHub.app, key) + ("X-Goog-FieldMask" to "location")))
                val l = o.getJSONObject("location")
                p.copy(pos = LatLon(l.getDouble("latitude"), l.getDouble("longitude")))
            }
            session = UUID.randomUUID().toString()
            done(r.getOrNull())
        }
    }

    private fun google(q: String, near: LatLon?, key: String): List<Place> {
        val body = JSONObject().put("input", q).put("sessionToken", session)
        if (near != null) {
            val c = JSONObject().put("latitude", near.lat).put("longitude", near.lon)
            body.put("origin", c)
            body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", c).put("radius", 50_000.0)))
        }
        val o = JSONObject(Http.post("https://places.googleapis.com/v1/places:autocomplete", body.toString(), GoogleAuth.headers(DashHub.app, key)))
        val s = o.optJSONArray("suggestions") ?: JSONArray()
        return (0 until s.length()).mapNotNull { i ->
            val p = s.getJSONObject(i).optJSONObject("placePrediction") ?: return@mapNotNull null
            val f = p.optJSONObject("structuredFormat")
            Place(
                name = f?.optJSONObject("mainText")?.optString("text") ?: p.getJSONObject("text").getString("text"),
                detail = f?.optJSONObject("secondaryText")?.optString("text").orEmpty(),
                pos = null,
                googleId = p.getString("placeId"),
                distM = if (p.has("distanceMeters")) p.getDouble("distanceMeters") else null,
            )
        }
    }

    private fun photon(q: String, near: LatLon?): List<Place> {
        // Strong preference for places near the rider (low bias scale = distance matters more than fame).
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&location_bias_scale=0.1&zoom=12" } ?: ""
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=15&lang=en$bias"
        val features = JSONObject(Http.get(url)).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val name = p.optString("name").ifBlank { listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ") }
            if (name.isBlank()) return@mapNotNull null
            val detail = listOf(p.optString("street").takeIf { it != name }, p.optString("district"), p.optString("city"), p.optString("state"))
                .filter { !it.isNullOrBlank() }.distinct().joinToString(", ")
            val pos = LatLon(c.getDouble(1), c.getDouble(0))
            Place(name, detail, pos, distM = near?.let { Geo.dist(it, pos) })
        }.distinctBy { it.name + it.detail }
            .sortedBy { it.distM ?: 0.0 } // nearest first
            .take(8)
    }
}

object Http {
    /** Names the app to the free services (OpenStreetMap, OpenFreeMap ask for this). */
    const val UA = "RogerDash/1.0 (open-source motorcycle app; https://github.com/rooo1/RogerDash)"

    /** Plain GET with a generic user agent (no personal details). */
    fun get(url: String, timeoutMs: Int = 12_000, headers: Map<String, String> = emptyMap()): String =
        call(url, "GET", null, headers, timeoutMs)

    fun post(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): String =
        call(url, "POST", json, headers + ("Content-Type" to "application/json"), timeoutMs)

    private fun call(url: String, method: String, body: String?, headers: Map<String, String>, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException("HTTP ${c.responseCode}${msg?.let { ": $it" } ?: ""}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
