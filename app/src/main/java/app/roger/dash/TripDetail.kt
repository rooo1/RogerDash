package app.roger.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import java.util.Locale

/** One ride: route, numbers, speed graph. */
class TripDetail(private val a: Activity, private val ui: Ui, private val t: Trip, private val close: () -> Unit) {

    // Built on first use, after the map and sub-trip fields below are ready.
    val view: View by lazy {
        ScrollView(a).apply {
            isFillViewport = true
            addView(content())
        }
    }

    private fun content() = ui.column(
        ui.link("‹ Rides") { close() }.apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) },
        ui.text(t.name, 26f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setOnClickListener { rename() }
        },
        ui.text("${Fmt.day(t.start)} · ${Fmt.time(t.start)} – ${Fmt.time(t.end)}" +
            if (DashHub.showVehicles) " · ${DashHub.vehicleName(t.vehicle)}" else "", 13.5f, ui.soft).apply {
            setPadding(0, ui.dp(4), 0, ui.dp(22))
        },
        fromTo.also { lookUpPlaces() },
        map ?: ui.text(if (t.source == "bike") "Recorded from the bike (no route: Location was off)." else "No route recorded.", 13.5f, ui.soft),
        speedKey(),
        legShown,
        routeFacts(),
        ui.big("%.1f".format(Locale.US, t.distanceKm), "km", 56f).apply { tag = Ui.SECTION },
        ui.statRow(
            Fmt.dur(t.movingSec) to "riding",
            "${t.avgKmh.toInt()}" to "avg km/h",
            "${t.topKmh}" to "top km/h",
        ),
        ui.statRow(
            Fmt.dur(t.totalSec) to "total time",
            Fmt.dur(t.stoppedSec) to "stopped",
            (fuelUsed() ?: "—") to "fuel bars used",
        ),
        if (t.startOdo != null && t.endOdo != null) ui.statRow(
            "%,d".format(Locale.US, t.startOdo) to "odometer start",
            "%,d".format(Locale.US, t.endOdo) to "odometer end",
            (if (t.source == "bike") "bike" else "GPS") to "measured by",
        ) else ui.space(1),
        legsCard(),
        if (t.speeds.size >= 2) speedGraph() else ui.space(1),
        ui.label("Actions"),
        ui.row(
            ui.secondary("Share picture") { sharePicture() },
            ui.secondary("Export GPX") { exportGpx() },
        ),
        ui.space(8),
        ui.row(
            ui.secondary(if (t.favourite) "★ Favourite" else "☆ Favourite") { toggleFav() },
            ui.secondary("Delete") { delete() },
        ),
        ui.space(8),
        if (t.points.isNotEmpty()) ui.link("Open end point in Maps ›") {
            val p = t.points.last()
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")))
        } else ui.space(1),
        ui.space(48).apply { tag = Ui.END },
    ).apply {
        setPadding(ui.dp(24), ui.dp(18), ui.dp(24), 0)
        ui.cardify(this) // stats, speed and actions as cards; the map stays as it is above them
    }

    private val whole = t.points.map { LatLon(it.lat, it.lon) }
    private val map: RouteMap? = if (whole.size >= 2) RouteMap(a, ui, 0.75f).apply { showSpeedRoute(t.points) } else null

    /** Under the map: what the line colours mean. */
    private fun speedKey(): View {
        if (map == null) return ui.space(1)
        val row = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, 0)
        }
        RouteMap.SPEED_BANDS.forEach { (label, color) ->
            row.addView(ui.column(
                View(a).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = ui.dpf(3f) }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, ui.dp(6))
                },
                ui.text(label, 11.5f, ui.soft).apply { gravity = android.view.Gravity.CENTER; setPadding(0, ui.dp(3), 0, 0) },
            ).apply { setPadding(ui.dp(2), 0, ui.dp(2), 0) }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        return ui.column(row, ui.text("Line colour = your speed in km/h", 11.5f, ui.soft).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, ui.dp(2), 0, ui.dp(6))
        })
    }

    /** "From X → Y", filled in from the phone's address lookup once it answers. */
    private val fromTo = ui.text("", 15f).apply { visibility = View.GONE; setPadding(0, 0, 0, ui.dp(12)) }

    private fun lookUpPlaces() {
        val first = t.points.firstOrNull() ?: return
        val last = t.points.last()
        Thread {
            val name = { p: Pt ->
                runCatching {
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(a).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { ad ->
                        ad.subLocality ?: ad.locality ?: ad.thoroughfare ?: ad.subAdminArea
                    }
                }.getOrNull()
            }
            val from = name(first)
            val to = name(last)
            if (from == null && to == null) return@Thread
            a.runOnUiThread {
                fromTo.text = if (from == to) "Around ${from ?: to}" else "${from ?: "?"}  →  ${to ?: "?"}"
                fromTo.visibility = View.VISIBLE
            }
        }.start()
    }

    /** The GPS point closest in time to [sec] seconds into the ride. */
    private fun pointAt(sec: Int): Pt? = t.points.minByOrNull { Math.abs(it.t - sec) }

    private fun speedGraph(): View {
        val g = SpeedView(a, ui, t.speeds).apply {
            labelFor = { i -> "${Fmt.time(t.start + i * 10_000L)}  ·  ${t.speeds[i]} km/h" }
            onScrub = { i -> map?.showPosition(i?.let { pointAt(it * 10) }?.let { LatLon(it.lat, it.lon) }) }
        }
        return ui.column(
            ui.label("Speed").apply { setPadding(0, 0, 0, ui.dp(8)) },
            g,
            ui.text(if (map != null) "Slide your finger along the graph: the dot on the map shows where you were." else "Slide your finger along the graph to see the speed.",
                12f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) },
        ).apply { tag = Ui.SECTION }
    }

    /** Under the map while one sub-trip is shown: its name and a way back to the whole ride. */
    private val legShown = ui.row(ui.text("", 13.5f, ui.soft), ui.link("Whole ride ›") { showLeg(null) }.apply { gravity = android.view.Gravity.END })
        .apply { visibility = View.GONE }

    /** Show one sub-trip's piece of the route on the map (null = the whole ride). */
    private fun showLeg(l: Leg?) {
        val m = map ?: return
        val track = l?.track(t.points)?.takeIf { it.size >= 2 } ?: t.points
        m.showSpeedRoute(track)
        m.fitTo(track.map { LatLon(it.lat, it.lon) })
        legShown.visibility = if (l != null) View.VISIBLE else View.GONE
        ((legShown as android.view.ViewGroup).getChildAt(0) as android.widget.TextView).text =
            l?.let { "Showing: " + listOfNotNull(it.from, it.to).joinToString(" → ") } ?: ""
        (view as ScrollView).smoothScrollTo(0, 0)
    }

    /** The navigations started during this ride (sub-trips). Tap one to see its route. */
    private fun legsCard(): View {
        if (t.legs.isEmpty()) return ui.space(1)
        val col = ui.column(ui.label("Navigated").apply { setPadding(0, 0, 0, ui.dp(8)) })
        t.legs.forEachIndexed { i, l ->
            if (i > 0) col.addView(ui.divider())
            col.addView(ui.column(
                ui.text(listOfNotNull(l.from, l.to).joinToString(" → "), 16f),
                ui.text(listOfNotNull(
                    "${Fmt.time(l.start)}–${Fmt.time(l.end)}", Fmt.km(l.distanceKm), Fmt.dur(l.totalSec),
                    "${l.avgKmh.toInt()} avg · ${l.topKmh} top",
                ).joinToString(" · "), 12.5f, ui.soft),
                ui.text(when (l.ended) { "arrived" -> "✓ Arrived"; "cancelled" -> "Stopped before arriving"; else -> "Ride ended during navigation" },
                    12.5f, if (l.arrived) ui.ok else ui.accent),
            ).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)); setOnClickListener { showLeg(l) } })
        }
        if (map != null) col.addView(ui.text("Tap one to see its route on the map.", 12f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) })
        return col.apply { tag = Ui.SECTION }
    }

    /** Top-speed spot and stops, under the map (tap to open the spot in a maps app). */
    private fun routeFacts(): View {
        val col = ui.column()
        Track.topPoint(t)?.let { p ->
            col.addView(ui.link("Top speed ${p.kmh.toInt()} km/h at ${Fmt.time(t.start + p.t * 1000L)} ›") { openSpot(p.lat, p.lon, "Top speed") })
        }
        val stops = Track.stops(t)
        if (stops.isNotEmpty()) {
            col.addView(ui.text("${stops.size} stop${if (stops.size > 1) "s" else ""} · ${Fmt.dur(stops.sumOf { it.sec })} standing", 13.5f, ui.soft)
                .apply { setPadding(0, ui.dp(4), 0, 0) })
            stops.take(5).forEach { s ->
                col.addView(ui.link("${Fmt.time(t.start + s.atSec * 1000L)} · ${Fmt.dur(s.sec)} ›") { openSpot(s.lat, s.lon, "Stop") }
                    .apply { textSize = 13.5f; setPadding(0, ui.dp(6), 0, ui.dp(6)) })
            }
        }
        return col
    }

    private fun openSpot(lat: Double, lon: Double, label: String) = runCatching {
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")))
    }

    private fun sharePicture() {
        val f = runCatching { RideCard.save(t, a) }.getOrElse { toast("Couldn't make the picture"); return }
        Share.send(a, f, t.name, "${t.name}: ${Fmt.km(t.distanceKm)}, top ${t.topKmh} km/h")
    }

    private fun exportGpx() {
        if (t.points.isEmpty()) { toast("This ride has no route to export (Location was off)"); return }
        val f = Share.newFile(a, "${Share.slug(t.name)}-${t.id}.gpx")
        f.writeText(Gpx.of(listOf(t)))
        Share.send(a, f, "${t.name}.gpx")
    }

    private fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show()

    private fun fuelUsed(): String? {
        val s = t.fuelStart ?: return null
        val e = t.fuelEnd ?: return null
        return "${(s - e).coerceAtLeast(0)}"
    }

    private fun rename() {
        val f = EditText(a).apply { setText(t.name); setSelection(t.name.length) }
        AlertDialog.Builder(a).setTitle("Name this ride").setView(ui.column(f).apply { setPadding(ui.dp(22), 0, ui.dp(22), 0) })
            .setPositiveButton("Save") { _, _ ->
                t.name = f.text.toString().ifBlank { t.name }
                DashHub.store.save(t); close()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleFav() {
        t.favourite = !t.favourite
        DashHub.store.save(t)
        close()
    }

    private fun delete() {
        AlertDialog.Builder(a).setMessage("Delete this ride?")
            .setPositiveButton("Delete") { _, _ -> DashHub.store.delete(t); close() }
            .setNegativeButton("Cancel", null).show()
    }
}
