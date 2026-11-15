package app.roger.dash

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The rider's own Google key is restricted to this Android app. Google checks that on web-service calls
 * (Places, Routes) through these two headers: our package name and our signing certificate's SHA-1.
 */
object GoogleAuth {
    @Volatile private var cert: String? = null

    fun headers(ctx: Context, key: String): Map<String, String> = mapOf("X-Goog-Api-Key" to key) + androidHeaders(ctx)

    /** The app's identity: Google checks it against the key's Android-app restriction. */
    fun androidHeaders(ctx: Context): Map<String, String> = mapOf(
        "X-Android-Package" to ctx.packageName,
        "X-Android-Cert" to sha1(ctx),
    )

    private fun sha1(ctx: Context): String = cert ?: runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        MessageDigest.getInstance("SHA-1").digest(sig).joinToString("") { "%02X".format(it) }
    }.getOrDefault("").also { cert = it }

    /**
     * This installed app's SHA-1 fingerprint as Google Cloud Console asks for it ("AB:CD:…"). It differs between
     * a build someone made themselves and the released one, so the key must allow the one that's installed.
     */
    fun fingerprint(ctx: Context): String = sha1(ctx).chunked(2).joinToString(":")

    /** What Google said the last time it refused the key (null: the last call worked, or none was made). */
    @Volatile var problem: String? = null
        private set

    /** A Google call failed: keep the reason if it's about the key (not a network blip), for More → Google Maps. */
    fun failed(e: Throwable) {
        val m = e.message.orEmpty()
        if (Regex("HTTP (400|401|403)").containsMatchIn(m)) {
            android.util.Log.w("Dash", "Google refused: $m")
            if (problem != m) { problem = m; DashHub.changed() }
        }
    }

    fun worked() { if (problem != null) { problem = null; DashHub.changed() } }
}

/**
 * Google Routes API, two-wheeler mode (India), traffic-aware: Google's own roads and timings.
 * The result is turned into our [Route] so the same navigator, dash arrows and voice work with it.
 */
object GoogleRoutes {
    private const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELDS = "routes.distanceMeters,routes.duration,routes.legs.steps.navigationInstruction," +
        "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.distanceMeters," +
        "routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"

    /** Blocking. [points]: start, stops, destination. */
    fun route(ctx: Context, key: String, points: List<LatLon>, heading: Float?, o: RouteOptions): Route =
        routes(ctx, key, points, heading, o, alternatives = false).first()

    /** With [alternatives] (only possible without stops): Google's best route plus up to two others. */
    fun routes(ctx: Context, key: String, points: List<LatLon>, heading: Float?, o: RouteOptions, alternatives: Boolean): List<Route> {
        fun waypoint(p: LatLon, h: Float? = null) = JSONObject().put("location", JSONObject()
            .put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lon))
            .apply { if (h != null) put("heading", h.toInt()) })
        val body = JSONObject()
            .put("origin", waypoint(points.first(), heading))
            .put("destination", waypoint(points.last()))
            .put("travelMode", "TWO_WHEELER")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("languageCode", "en-IN")
            .put("units", "METRIC")
            .put("polylineQuality", "HIGH_QUALITY")
            .put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE")) // traffic colours along the route
            .put("routeModifiers", JSONObject().put("avoidTolls", o.avoidTolls).put("avoidHighways", o.noHighways).put("avoidFerries", true))
        if (points.size > 2) body.put("intermediates", JSONArray(points.subList(1, points.size - 1).map { waypoint(it) }))
        else if (alternatives) body.put("computeAlternativeRoutes", true)
        val res = Http.post(URL, body.toString(), GoogleAuth.headers(ctx, key) + ("X-Goog-FieldMask" to FIELDS), timeoutMs = 15_000)
        val o = JSONObject(res)
        val n = o.optJSONArray("routes")?.length() ?: 0
        if (n == 0) throw IllegalStateException("No route")
        return (0 until n).mapNotNull { i -> runCatching { parse(o, i) }.getOrNull() }.ifEmpty { listOf(parse(o, 0)) }
    }

    /** Google's steps → one shape + our maneuvers (a step's instruction happens where the step starts). */
    fun parse(o: JSONObject, index: Int = 0): Route {
        val r = o.optJSONArray("routes")?.optJSONObject(index) ?: throw IllegalStateException("No route")
        val shape = ArrayList<LatLon>()
        val man = ArrayList<Maneuver>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: JSONArray()
            val starts = ArrayList<Pair<Int, JSONObject>>()
            for (si in 0 until steps.length()) {
                val st = steps.getJSONObject(si)
                val pts = Geo.decode(st.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), 5)
                if (pts.isEmpty()) continue
                val begin = if (shape.isEmpty()) 0 else shape.size - 1
                shape.addAll(if (shape.isEmpty()) pts else pts.drop(1))
                starts += begin to st
            }
            starts.forEachIndexed { k, (begin, st) ->
                val ni = st.optJSONObject("navigationInstruction")
                val m = ni?.optString("maneuver").orEmpty()
                val text = ni?.optString("instructions").orEmpty().lineSequence().firstOrNull().orEmpty().trim()
                val type = if (m == "DEPART" || (li == 0 && k == 0)) 1 else 0
                val next = starts.getOrNull(k + 1)?.first ?: (shape.size - 1)
                val icon = if (m.startsWith("ROUNDABOUT")) Router.roundaboutIcon(shape, begin, next) ?: Frames.Icon.ENTER_ROUNDABOUT else icon(m)
                man += Maneuver(icon, text.ifBlank { m.lowercase().replace('_', ' ') }, begin, type,
                    street = street(text), verbalAlert = text.ifBlank { null }, verbalNow = text.ifBlank { null })
            }
            // End of the leg: a stop on the way, or the destination.
            val last = li == legs.length() - 1
            man += Maneuver(Frames.Icon.ARRIVE, if (last) "Arrive at your destination" else "Arrive at stop ${li + 1}",
                (shape.size - 1).coerceAtLeast(0), 4, verbalAlert = if (last) "Your destination is ahead" else "Your stop is ahead",
                verbalNow = if (last) null else "You've reached your stop", isStop = !last)
        }
        if (shape.size < 2) throw IllegalStateException("Empty route")
        val secs = r.optString("duration").removeSuffix("s").toDoubleOrNull() ?: 0.0
        return Route(shape, man, r.optDouble("distanceMeters", 0.0), secs, traffic(r, shape))
    }

    /**
     * Google gives traffic as intervals over the route's overall polyline (point indices). Our [shape] is the
     * steps joined, which is the same line but its points may not match one to one, so each of our pieces takes
     * the traffic of the nearest point of the overall polyline (searched forward, so loops don't confuse it).
     */
    private fun traffic(r: JSONObject, shape: List<LatLon>): List<Int> {
        val iv = r.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals") ?: return emptyList()
        val line = Geo.decode(r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), 5)
        if (line.size < 2 || iv.length() == 0) return emptyList()
        val lineSpeed = IntArray(line.size - 1)
        for (i in 0 until iv.length()) {
            val o = iv.getJSONObject(i)
            val code = when (o.optString("speed")) { "SLOW" -> 1; "TRAFFIC_JAM" -> 2; else -> 0 }
            for (k in o.optInt("startPolylinePointIndex", 0) until o.optInt("endPolylinePointIndex", 0).coerceAtMost(lineSpeed.size)) lineSpeed[k] = code
        }
        var j = 0
        return (0 until shape.size - 1).map { i ->
            // Advance along the overall line while the next point is closer to this piece's start.
            while (j < line.size - 2 && Geo.dist(line[j + 1], shape[i]) <= Geo.dist(line[j], shape[i])) j++
            lineSpeed[j.coerceAtMost(lineSpeed.size - 1)]
        }
    }

    /** Google maneuver → dashboard icon (checked against the dash's own icon set). */
    fun icon(m: String): Int = when (m) {
        "TURN_LEFT" -> Frames.Icon.LEFT
        "TURN_RIGHT" -> Frames.Icon.RIGHT
        "TURN_SLIGHT_LEFT" -> Frames.Icon.SLIGHT_LEFT
        "TURN_SLIGHT_RIGHT" -> Frames.Icon.SLIGHT_RIGHT
        "TURN_SHARP_LEFT" -> Frames.Icon.SHARP_LEFT
        "TURN_SHARP_RIGHT" -> Frames.Icon.SHARP_RIGHT
        "UTURN_LEFT" -> Frames.Icon.U_TURN
        "UTURN_RIGHT" -> Frames.Icon.U_TURN_RIGHT
        "RAMP_LEFT" -> Frames.Icon.OFF_RAMP_LEFT
        "RAMP_RIGHT" -> Frames.Icon.OFF_RAMP_RIGHT
        "FORK_LEFT" -> Frames.Icon.KEEP_LEFT
        "FORK_RIGHT" -> Frames.Icon.KEEP_RIGHT
        "FERRY", "FERRY_TRAIN" -> Frames.Icon.FERRY
        else -> Frames.Icon.STRAIGHT // DEPART, STRAIGHT, MERGE, NAME_CHANGE
    }

    /** "Turn left onto MG Road" → "MG Road". */
    fun street(text: String): String? =
        Regex("\\b(?:onto|on to|on|toward|towards)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?.substringBefore(" Pass by")?.substringBefore("/")?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() && it.length < 60 }
}
