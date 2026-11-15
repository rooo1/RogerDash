package app.roger.dash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * The map: route on top, the rider's arrow, and (in [navigation] mode) a camera that follows like a car
 * navigator: turned to the direction of travel, tilted, zoomed by speed. Panning stops following until [recenter].
 *
 * Two engines behind one face:
 *  - Google Maps (latest Google map, live traffic) when the rider has built in their own key (MAPS_API_KEY);
 *  - otherwise MapLibre with OpenFreeMap (OpenStreetMap data; free, no key).
 */
@SuppressLint("ViewConstructor")
class RouteMap(
    a: Activity,
    private val ui: Ui,
    private val heightRatio: Float = 0.7f,
    private val navigation: Boolean = false,
    /** Full-screen browse map (Navigate home): fills its parent, no rounded corners. */
    private val fill: Boolean = false,
) : FrameLayout(a) {

    /** Places to show as pins (search results); [onPlaceTap] gets the tapped one's index. */
    internal var places: List<LatLon> = emptyList()
    internal var selectedPlace = -1
    var onPlaceTap: ((Int) -> Unit)? = null

    fun showPlaces(points: List<LatLon>, selected: Int = -1) {
        if (points == places && selected == selectedPlace) return
        places = points; selectedPlace = selected; engine.draw(animate = false)
    }

    /** Space covered by the search bar (top) and the sheet (bottom), so the camera centres in what's visible. */
    internal var padTop = 0
    internal var padBottom = 0
    fun setMapPadding(top: Int, bottom: Int) { padTop = top; padBottom = bottom; engine.draw(animate = false) }

    /** Move the camera to show all [points]. */
    fun fitTo(points: List<LatLon>) { if (points.isNotEmpty()) engine.fit(points) }

    /** Where a non-navigation follow camera last went (so it isn't re-animated for every tiny update). */
    internal var camAt: LatLon? = null

    /** Told when following stops (rider panned the map) or starts again. */
    var onFollowChanged: ((Boolean) -> Unit)? = null

    internal var route: List<LatLon> = emptyList()
    internal var me: LatLon? = null
    internal var heading: Float? = null
    internal var speedMps = 0f
    internal var follow = navigation

    /** Google's map when the app has a map key built in and the rider didn't switch it off (More → Google Maps). */
    private val engine: Engine = if (googleMapKey(a) && DashHub.googleMap) GoogleEngine(a, this, ui, navigation) else LibreEngine(a, this, ui, navigation)

    companion object {
        const val BLUE = "#1A73E8"
        const val BLUE_DARK = "#0B57D0"
        val SLOW = Color.parseColor("#F29900")  // orange, like Google Maps
        val JAM = Color.parseColor("#D93025")   // red
        val START = Color.parseColor("#188038") // green
        val END = Color.parseColor("#202124")   // near-black

        /** Speed colours, slow → fast (light amber to deep violet: one direction, so "darker = faster"). */
        val SPEED_BANDS: List<Pair<String, Int>> = listOf(
            "0–20" to Color.parseColor("#F4A72E"),
            "20–40" to Color.parseColor("#E5604E"),
            "40–60" to Color.parseColor("#C0367F"),
            "60–80" to Color.parseColor("#8A1BA3"),
            "80+" to Color.parseColor("#3F0A9E"),
        )

        fun speedColor(kmh: Float): Int = SPEED_BANDS[(kmh / 20).toInt().coerceIn(0, SPEED_BANDS.size - 1)].second

        /**
         * A Google Maps key was built into this app (Maps SDK for Android): debug builds and -Ppersonal release builds,
         * from local.properties MAPS_API_KEY. Never the public release.
         */
        fun googleMapKey(ctx: Context): Boolean = runCatching {
            ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY").orEmpty().startsWith("AIza")
        }.getOrDefault(false)
    }

    init {
        addView(engine.view, LayoutParams(-1, -1))
        if (!navigation && !fill) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ui.dpf(18f))
            }
            clipToOutline = true
        }
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = engine.start()
            override fun onViewDetachedFromWindow(v: View) = engine.stop()
        })
    }

    override fun onMeasure(w: Int, h: Int) {
        if (navigation || fill) { super.onMeasure(w, h); return }
        val width = MeasureSpec.getSize(w)
        super.onMeasure(w, MeasureSpec.makeMeasureSpec((width * heightRatio).toInt(), MeasureSpec.EXACTLY))
    }

    /** Let the map pan and zoom inside a scrolling page. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(e)
    }

    /** [trafficPerPiece]: 0 clear / 1 slow / 2 jam for each piece points[i]→points[i+1]; colours the line like Google Maps. */
    fun showRoute(points: List<LatLon>, trafficPerPiece: List<Int> = emptyList()) {
        route = points; traffic = trafficPerPiece; pieceColors = emptyList(); labelEnds = false; engine.draw(animate = false)
    }

    /** A recorded ride: the line coloured by speed ([SPEED_BANDS]), with "Start" and "End" pins. */
    fun showSpeedRoute(points: List<Pt>) {
        route = points.map { LatLon(it.lat, it.lon) }
        traffic = emptyList()
        pieceColors = (0 until points.size - 1).map { speedColor((points[it].kmh + points[it + 1].kmh) / 2f) }
        labelEnds = true
        engine.draw(animate = false)
    }

    internal var traffic: List<Int> = emptyList()
    /** Explicit colour per piece (speed colouring); empty = traffic colours. */
    internal var pieceColors: List<Int> = emptyList()
    /** Labelled "Start" / "End" pins instead of the grey dot and red pin. */
    internal var labelEnds = false

    /** Pin with a word on it ("Start", "End"); its point is at the bottom centre. */
    internal fun endPill(text: String, color: Int): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dpf(13f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val padX = ui.dpf(10f); val boxH = ui.dpf(26f); val tip = ui.dpf(7f); val ring = ui.dpf(2f)
        val w = (p.measureText(text) + padX * 2 + ring * 2).toInt()
        val h = (boxH + tip + ring * 2).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val shape = Path().apply {
            addRoundRect(android.graphics.RectF(ring, ring, w - ring, ring + boxH), boxH / 2, boxH / 2, Path.Direction.CW)
            moveTo(w / 2f - tip, ring + boxH - 1); lineTo(w / 2f, h - ring); lineTo(w / 2f + tip, ring + boxH - 1); close()
        }
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = ring * 2; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(shape, p)
        p.style = Paint.Style.FILL; p.color = color; c.drawPath(shape, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        c.drawText(text, w / 2f, ring + boxH / 2 - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    /** Stops on the way (fuel or any place), shown as numbered markers. */
    internal var stops: List<LatLon> = emptyList()

    fun showStops(points: List<LatLon>) {
        if (points == stops) return
        stops = points; engine.draw(animate = false)
    }

    /** Stop marker: orange disc with a white ring and its number. */
    internal fun stopBadge(n: Int): Bitmap {
        val size = ui.dp(30)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = Color.WHITE; c.drawCircle(r, r, r - ui.dpf(1f), p)
        p.color = ui.accent; c.drawCircle(r, r, r - ui.dpf(3.5f), p)
        p.color = Color.WHITE; p.textSize = ui.dpf(14f); p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("$n", r, r - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    /** Runs of the line with one traffic level: (colour, number of pieces). */
    internal fun trafficRuns(): List<Pair<Int, Int>> {
        val n = route.size - 1
        if (n < 1) return emptyList()
        val runs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) {
            val c = pieceColors.getOrNull(i) ?: when (traffic.getOrElse(i) { 0 }) { 1 -> SLOW; 2 -> JAM; else -> Color.parseColor(BLUE) }
            if (runs.isNotEmpty() && runs.last().first == c) runs[runs.size - 1] = c to runs.last().second + 1 else runs += c to 1
        }
        return runs
    }

    /** Rider's position and (if moving) direction. In a preview, [followRider] keeps it centred. */
    fun showPosition(p: LatLon?, followRider: Boolean = false, bearing: Float? = null, speedMps: Float? = null) {
        me = p
        if (bearing != null) heading = bearing
        this.speedMps = speedMps ?: 0f
        if (!navigation) follow = followRider
        engine.draw(animate = true)
    }

    /** Back to following the rider after panning around. */
    fun recenter() { follow = true; onFollowChanged?.invoke(true); engine.draw(animate = true) }

    internal fun userPanned() { if (follow) { follow = false; onFollowChanged?.invoke(false) } }

    /** Camera zoom for the rider's speed: close in town, wider on the highway. */
    internal fun navZoom(): Double { val kmh = speedMps * 3.6; return when { kmh > 80 -> 15.3; kmh > 50 -> 15.9; kmh > 25 -> 16.5; else -> 17.0 } }

    /** Rider marker: white-ringed blue disc, with a white arrow when we know the direction. */
    internal fun puck(arrow: Boolean): Bitmap {
        val size = ui.dp(if (arrow) 46 else 26)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = Color.argb(60, 0, 0, 0); c.drawCircle(r, r + ui.dpf(1f), r - ui.dpf(1f), p)
        p.color = Color.WHITE; c.drawCircle(r, r, r - ui.dpf(2f), p)
        p.color = Color.parseColor(BLUE); c.drawCircle(r, r, r - ui.dpf(5f), p)
        if (arrow) {
            p.color = Color.WHITE
            val w = size * 0.26f
            c.drawPath(Path().apply {
                moveTo(r, r - w * 1.25f); lineTo(r + w, r + w); lineTo(r, r + w * 0.45f); lineTo(r - w, r + w); close()
            }, p)
        }
        return b
    }

    internal interface Engine {
        val view: View
        fun start()
        fun stop()
        fun draw(animate: Boolean)
        fun fit(points: List<LatLon>)
    }

    /** Non-navigation follow: true when the camera should move to [p] (first time, or moved > 5 m). */
    internal fun shouldRecentre(p: LatLon): Boolean {
        val last = camAt
        if (last != null && Geo.dist(last, p) < 5) return false
        camAt = p; return true
    }
}

// ───────────── Google Maps ─────────────

private class GoogleEngine(a: Activity, private val m: RouteMap, private val ui: Ui, private val navigation: Boolean) : RouteMap.Engine {
    private val mv = com.google.android.gms.maps.MapView(a, com.google.android.gms.maps.GoogleMapOptions()
        .mapToolbarEnabled(false).compassEnabled(false))
    override val view: View get() = mv
    private var map: com.google.android.gms.maps.GoogleMap? = null
    private var casing: com.google.android.gms.maps.model.Polyline? = null
    private var line: com.google.android.gms.maps.model.Polyline? = null
    private var dest: com.google.android.gms.maps.model.Marker? = null
    private var start: com.google.android.gms.maps.model.Circle? = null
    private var puck: com.google.android.gms.maps.model.Marker? = null
    private var puckArrow: Boolean? = null
    private var fitted = false
    private var shownRoute: List<LatLon>? = null
    private var shownTraffic: List<Int>? = null
    private var shownColors: List<Int>? = null
    private var startPin: com.google.android.gms.maps.model.Marker? = null
    private var endPin: com.google.android.gms.maps.model.Marker? = null
    private var shownStops: List<LatLon>? = null
    private val stopMarkers = ArrayList<com.google.android.gms.maps.model.Marker>()
    private var shownPlaces: Pair<List<LatLon>, Int>? = null
    private val placeMarkers = ArrayList<com.google.android.gms.maps.model.Marker>()
    private var pendingFit: List<LatLon>? = null

    override fun fit(points: List<LatLon>) {
        val g = map ?: run { pendingFit = points; return }
        if (points.size == 1) { g.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(ll(points[0]), 15f)); return }
        val b = com.google.android.gms.maps.model.LatLngBounds.builder()
        points.forEach { b.include(ll(it)) }
        m.post { runCatching { g.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(b.build(), ui.dp(48))) } }
    }

    init {
        com.google.android.gms.maps.MapsInitializer.initialize(a, com.google.android.gms.maps.MapsInitializer.Renderer.LATEST) {}
        mv.onCreate(null)
        mv.getMapAsync { g ->
            map = g
            g.uiSettings.apply {
                isCompassEnabled = false
                isMapToolbarEnabled = false
                isMyLocationButtonEnabled = false
                isRotateGesturesEnabled = navigation
                isTiltGesturesEnabled = navigation
            }
            if (ui.dark) runCatching { g.mapColorScheme = com.google.android.gms.maps.model.MapColorScheme.DARK }
            g.isTrafficEnabled = navigation // live traffic colours on the roads while navigating
            g.isBuildingsEnabled = true
            g.setOnCameraMoveStartedListener { reason ->
                if (reason == com.google.android.gms.maps.GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) m.userPanned()
            }
            g.setOnMarkerClickListener { mk -> (mk.tag as? Int)?.let { m.onPlaceTap?.invoke(it); true } ?: false }
            draw(animate = false)
            pendingFit?.let { fit(it); pendingFit = null }
        }
    }

    override fun start() { mv.onStart(); mv.onResume() }
    /** Destroyed: later draw calls must not touch the old map. */
    override fun stop() { map = null; mv.onPause(); mv.onStop(); mv.onDestroy() }

    private fun ll(p: LatLon) = com.google.android.gms.maps.model.LatLng(p.lat, p.lon)

    override fun draw(animate: Boolean) {
        val g = map ?: return
        val r = m.route
        if (r.size >= 2 && (r !== shownRoute || m.traffic !== shownTraffic || m.pieceColors !== shownColors)) {
            shownRoute = r; shownTraffic = m.traffic; shownColors = m.pieceColors
            val pts = r.map { ll(it) }
            val w = ui.dpf(if (navigation) 9f else 6f)
            if (casing == null) {
                casing = g.addPolyline(com.google.android.gms.maps.model.PolylineOptions().addAll(pts).width(w + ui.dpf(4f))
                    .color(Color.parseColor(RouteMap.BLUE_DARK)).jointType(com.google.android.gms.maps.model.JointType.ROUND).zIndex(1f))
                line = g.addPolyline(com.google.android.gms.maps.model.PolylineOptions().addAll(pts).width(w)
                    .color(Color.parseColor(RouteMap.BLUE)).jointType(com.google.android.gms.maps.model.JointType.ROUND).zIndex(2f))
            } else { casing!!.points = pts; line!!.points = pts }
            // Speed colours sit on a white edge (readable on light and dark maps); routes keep the dark blue edge.
            casing!!.color = if (m.pieceColors.isNotEmpty()) Color.WHITE else Color.parseColor(RouteMap.BLUE_DARK)
            // Traffic along the route: blue clear, orange slow, red jam (each run of pieces one colour).
            line!!.spans = m.trafficRuns().map { (c, n) ->
                com.google.android.gms.maps.model.StyleSpan(c, n.toDouble())
            }
            // Recorded ride: "Start" and "End" pins.
            startPin?.remove(); startPin = null
            if (m.labelEnds) {
                dest?.remove(); dest = null
                start?.remove(); start = null
                endPin?.remove()
                startPin = g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(r.first())).anchor(0.5f, 1f).zIndex(6f)
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(m.endPill("Start", RouteMap.START))))
                endPin = g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(r.last())).anchor(0.5f, 1f).zIndex(7f)
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(m.endPill("End", RouteMap.END))))
            } else {
            endPin?.remove(); endPin = null
            // Destination: Google's own red pin. Start (previews only): a grey dot.
            if (dest == null) dest = g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(r.last())))
            else dest!!.position = ll(r.last())
            }
            if (!navigation && !m.labelEnds) {
                start?.remove()
                start = g.addCircle(com.google.android.gms.maps.model.CircleOptions().center(ll(r.first())).radius(12.0)
                    .fillColor(Color.parseColor("#5F6368")).strokeColor(Color.WHITE).strokeWidth(ui.dpf(2f)).zIndex(3f))
            }
        }
        if (!navigation) g.setPadding(0, m.padTop, 0, m.padBottom)
        if (shownPlaces != m.places to m.selectedPlace) {
            shownPlaces = m.places to m.selectedPlace
            placeMarkers.forEach { it.remove() }
            placeMarkers.clear()
            m.places.forEachIndexed { i, s ->
                val sel = i == m.selectedPlace
                g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(s)).zIndex(if (sel) 6f else 4f)
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
                        if (sel) com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE
                        else com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)))
                    ?.let { it.tag = i; placeMarkers += it }
            }
        }
        if (m.stops != shownStops) {
            shownStops = m.stops
            stopMarkers.forEach { it.remove() }
            stopMarkers.clear()
            m.stops.forEachIndexed { i, s ->
                g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(s)).anchor(0.5f, 0.5f).zIndex(4f)
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(m.stopBadge(i + 1))))?.let { stopMarkers += it }
            }
        }
        val p = m.me
        val h = m.heading
        if (p == null) { puck?.remove(); puck = null }
        else {
            val arrow = h != null
            if (puck == null || puckArrow != arrow) {
                puck?.remove()
                puck = g.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(ll(p)).flat(true).anchor(0.5f, 0.5f)
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(m.puck(arrow))).zIndex(5f))
                puckArrow = arrow
            }
            puck?.position = ll(p)
            puck?.rotation = h ?: 0f
        }
        when {
            navigation && m.follow && p != null -> {
                // Bottom padding keeps Google's logo above the arrival bar (it must stay visible);
                // a large top padding puts the rider low on the screen, with more road ahead.
                g.setPadding(0, (m.height * 0.55).toInt(), 0, ui.dp(124))
                val cam = com.google.android.gms.maps.model.CameraPosition.Builder().target(ll(p)).zoom(m.navZoom().toFloat())
                    .tilt(50f).bearing(h ?: g.cameraPosition.bearing).build()
                val u = com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(cam)
                if (animate) g.animateCamera(u, 950, null) else g.moveCamera(u)
            }
            !navigation && m.follow && p != null -> if (m.shouldRecentre(p))
                g.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(ll(p), 15.5f), 600, null)
            !navigation && r.size >= 2 && !fitted -> {
                fitted = true
                val b = com.google.android.gms.maps.model.LatLngBounds.builder()
                r.forEach { b.include(ll(it)) }
                m.post { runCatching { g.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(b.build(), ui.dp(36))) } }
            }
        }
    }
}

// ───────────── MapLibre + OpenStreetMap ─────────────

private class LibreEngine(a: Activity, private val m: RouteMap, private val ui: Ui, private val navigation: Boolean) : RouteMap.Engine {
    private val mv: org.maplibre.android.maps.MapView
    override val view: View get() = mv
    private var map: org.maplibre.android.maps.MapLibreMap? = null
    private var style: org.maplibre.android.maps.Style? = null
    private var fitted = false

    init {
        org.maplibre.android.MapLibre.getInstance(a)
        mv = org.maplibre.android.maps.MapView(a)
        mv.onCreate(null)
        mv.getMapAsync { g ->
            map = g
            g.uiSettings.apply {
                isRotateGesturesEnabled = navigation
                isTiltGesturesEnabled = navigation
                isCompassEnabled = false // heading-up map; the turn banner would cover it anyway
                isLogoEnabled = false
                isAttributionEnabled = true // OpenStreetMap credit is required
                if (navigation) setAttributionMargins(0, 0, ui.dp(14), ui.dp(118))
                if (navigation) attributionGravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            }
            g.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) m.userPanned()
            }
            // "liberty": the detailed, Google-like look; at night a dark detailed style.
            val styleUrl = "https://tiles.openfreemap.org/styles/" + if (ui.dark) "fiord" else "liberty"
            g.setStyle(org.maplibre.android.maps.Style.Builder().fromUri(styleUrl)) { s ->
                style = s
                s.addImage("puck-arrow", m.puck(arrow = true))
                s.addImage("puck-dot", m.puck(arrow = false))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("route"))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("ends"))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("me"))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("stops"))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("places"))
                s.addSource(org.maplibre.android.style.sources.GeoJsonSource("pins"))
                s.addImage("pin-start", m.endPill("Start", RouteMap.START))
                s.addImage("pin-end", m.endPill("End", RouteMap.END))
                s.addLayer(org.maplibre.android.style.layers.CircleLayer("place-dots", "places").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.circleRadius(8f),
                    org.maplibre.android.style.layers.PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(Color.WHITE),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2.5f),
                ))
                s.addLayer(org.maplibre.android.style.layers.LineLayer("route-casing", "route").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(Color.parseColor(RouteMap.BLUE_DARK)),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(if (navigation) 13f else 9f),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                ))
                s.addLayer(org.maplibre.android.style.layers.LineLayer("route-line", "route").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get("color")),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(if (navigation) 9f else 6f),
                    org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                ))
                s.addLayer(org.maplibre.android.style.layers.CircleLayer("ends-dot", "ends").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.circleRadius(org.maplibre.android.style.expressions.Expression.get("r")),
                    org.maplibre.android.style.layers.PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(Color.WHITE),
                    org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(3f),
                ))
                s.addLayer(org.maplibre.android.style.layers.SymbolLayer("end-pins", "pins").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                    org.maplibre.android.style.layers.PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                ))
                s.addLayer(org.maplibre.android.style.layers.SymbolLayer("stop-badges", "stops").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                ))
                s.addLayer(org.maplibre.android.style.layers.SymbolLayer("me-puck", "me").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                    org.maplibre.android.style.layers.PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("bearing")),
                    org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
                    org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                ))
                draw(animate = false)
            }
        }
    }

    override fun start() { mv.onStart(); mv.onResume() }
    /** Destroyed: later draw calls (a search box losing focus as the screen closes) must not touch the old map. */
    override fun stop() { style = null; map = null; mv.onPause(); mv.onStop(); mv.onDestroy() }

    override fun fit(points: List<LatLon>) {
        val g = map ?: return
        if (points.size == 1) {
            g.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(points[0].lat, points[0].lon), 15.0)); return
        }
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        points.forEach { b.include(org.maplibre.android.geometry.LatLng(it.lat, it.lon)) }
        m.post { runCatching {
            g.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), ui.dp(48), m.padTop + ui.dp(48), ui.dp(48), m.padBottom + ui.dp(48)))
        } }
    }

    override fun draw(animate: Boolean) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return // MapLibre throws on a style that's been replaced
        val g = map ?: return
        val r = m.route
        if (r.size >= 2) {
            // One line piece per traffic run, each with its colour.
            val feats = ArrayList<org.maplibre.geojson.Feature>()
            var at = 0
            for ((c, n) in m.trafficRuns()) {
                val pts = r.subList(at, at + n + 1).map { org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat) }
                feats += org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(pts)).apply {
                    addStringProperty("color", String.format("#%06X", 0xFFFFFF and c))
                }
                at += n
            }
            (s.getSource("route") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(feats))
            (s.getLayer("route-casing") as? org.maplibre.android.style.layers.LineLayer)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(if (m.pieceColors.isNotEmpty()) Color.WHITE else Color.parseColor(RouteMap.BLUE_DARK)))
            val pins = if (!m.labelEnds) emptyList() else listOf(r.first() to "pin-start", r.last() to "pin-end").map { (p, icon) ->
                org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("icon", icon) }
            }
            (s.getSource("pins") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(pins))
            val ends = ArrayList<org.maplibre.geojson.Feature>()
            if (!m.labelEnds) {
            if (!navigation) ends += org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(r.first().lon, r.first().lat)).apply {
                addStringProperty("color", "#5F6368"); addNumberProperty("r", 6)
            }
            ends += org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(r.last().lon, r.last().lat)).apply {
                addStringProperty("color", "#D93025"); addNumberProperty("r", if (navigation) 9 else 7)
            }
            }
            (s.getSource("ends") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(ends))
        }
        (s.getSource("places") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromFeatures(m.places.mapIndexed { i, pl ->
                org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(pl.lon, pl.lat))
                    .apply { addStringProperty("color", if (i == m.selectedPlace) RouteMap.BLUE else "#D93025") }
            }))
        (s.getSource("stops") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromFeatures(m.stops.mapIndexed { i, st ->
                val icon = "stop-${i + 1}"
                if (s.getImage(icon) == null) s.addImage(icon, m.stopBadge(i + 1))
                org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(st.lon, st.lat)).apply { addStringProperty("icon", icon) }
            }))
        val p = m.me
        val h = m.heading
        (s.getSource("me") as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(
            if (p == null) org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList())
            else org.maplibre.geojson.FeatureCollection.fromFeature(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", if (h != null) "puck-arrow" else "puck-dot")
                addNumberProperty("bearing", h ?: 0f)
            }))
        when {
            navigation && m.follow && p != null -> {
                val cam = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
                    .zoom(m.navZoom()).tilt(50.0)
                    .bearing((h ?: g.cameraPosition.bearing.toFloat()).toDouble())
                    .padding(0.0, m.height * 0.45, 0.0, 0.0)
                    .build()
                val u = org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(cam)
                if (animate) g.easeCamera(u, 950, false) else g.moveCamera(u)
            }
            !navigation && m.follow && p != null -> if (m.shouldRecentre(p)) g.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(p.lat, p.lon)).zoom(15.5)
                    .padding(0.0, m.padTop.toDouble(), 0.0, m.padBottom.toDouble()).build()), 600)
            !navigation && r.size >= 2 && !fitted -> {
                fitted = true
                val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                r.forEach { b.include(org.maplibre.android.geometry.LatLng(it.lat, it.lon)) }
                m.post { runCatching { g.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), ui.dp(36))) } }
            }
        }
    }
}
