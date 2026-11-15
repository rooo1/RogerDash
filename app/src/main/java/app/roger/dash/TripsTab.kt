package app.roger.dash

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** Totals for the week / month / all time, then every ride. */
class TripsTab(private val a: Activity, private val ui: Ui, private val openTrip: (Trip) -> Unit) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var period = Stats.WEEK
    private var key = ""

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24)) })
    }

    /** Ids of rides picked for deleting; null = not selecting. Long-press a ride to start. */
    private var selected: MutableSet<Long>? = null

    fun refresh() { key = ""; update() }

    /** Short buzz: [strong] when selection starts (long-press), a light tick when adding/removing. */
    private fun buzz(strong: Boolean) {
        val v = a.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching {
            v.vibrate(android.os.VibrationEffect.createPredefined(
                if (strong) android.os.VibrationEffect.EFFECT_HEAVY_CLICK else android.os.VibrationEffect.EFFECT_TICK))
        }
    }

    /** Monthly report being shown (year to month), or null for the normal list. */
    private var report: Pair<Int, Int>? = null

    /** Back button: leave the monthly report, then selection. */
    fun onBack(): Boolean {
        if (report != null) { report = null; refresh(); return true }
        if (selected == null) return false
        selected = null; refresh(); return true
    }

    private fun exportSelected(all: List<Trip>) {
        val ids = selected ?: return
        val picked = all.filter { it.id in ids }
        val withRoute = picked.filter { it.points.isNotEmpty() }
        if (withRoute.isEmpty()) {
            android.widget.Toast.makeText(a, "These rides have no route to export (Location was off)", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val f = Share.newFile(a, if (withRoute.size == 1) "${Share.slug(withRoute[0].name)}-${withRoute[0].id}.gpx"
            else "roger-dash-${withRoute.size}-rides.gpx")
        f.writeText(Gpx.of(withRoute))
        if (withRoute.size < picked.size) android.widget.Toast.makeText(a,
            "${picked.size - withRoute.size} of the rides have no route and were left out", android.widget.Toast.LENGTH_LONG).show()
        Share.send(a, f, if (withRoute.size == 1) "${withRoute[0].name}.gpx" else "${withRoute.size} rides (GPX)")
    }

    /** Monthly report: riding and fuel for one month, with ‹ › to other months. */
    private fun renderReport(all: List<Trip>, year: Int, month: Int) {
        val fuel = DashHub.store.fuel().let { f -> vehicleFilter?.let { id -> f.filter { it.vehicle == id } } ?: f }
        val r = MonthReport.of(year, month, all, fuel)
        box.addView(ui.link("‹ Trips") { report = null; refresh() }.apply { setPadding(0, 0, 0, ui.dp(6)) })
        fun go(delta: Int) {
            val c = java.util.Calendar.getInstance().apply { clear(); set(year, month, 1); add(java.util.Calendar.MONTH, delta) }
            report = c.get(java.util.Calendar.YEAR) to c.get(java.util.Calendar.MONTH); refresh()
        }
        val now = java.util.Calendar.getInstance()
        val isCurrent = year == now.get(java.util.Calendar.YEAR) && month == now.get(java.util.Calendar.MONTH)
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.link("‹") { go(-1) }.apply { textSize = 26f; setPadding(0, 0, ui.dp(16), 0) })
            addView(ui.text(r.title, 26f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (!isCurrent) addView(ui.link("›") { go(1) }.apply { textSize = 26f; setPadding(ui.dp(16), 0, 0, 0) })
        })
        box.addView(ui.big("%,.0f".format(Locale.US, r.km), "km", 52f).apply { tag = Ui.SECTION })
        box.addView(ui.statRow(
            "${r.rides.size}" to if (r.rides.size == 1) "ride" else "rides",
            Fmt.dur(r.movingSec) to "riding",
            (r.longest?.let { Fmt.km(it.distanceKm) } ?: "—") to "longest",
        ))
        box.addView(ui.label("Fuel"))
        if (r.fuelLitres > 0) {
            box.addView(ui.statRow(
                "₹%,.0f".format(Locale.US, r.fuelCost) to "spent",
                "%.1f L".format(Locale.US, r.fuelLitres) to "filled",
                (r.kmPerL?.let { "%.1f".format(Locale.US, it) } ?: "—") to "km/l",
            ))
            if (r.km > 0 && r.fuelCost > 0) box.addView(ui.text("About ₹%.2f per km ridden this month.".format(Locale.US, r.fuelCost / r.km), 13f, ui.soft)
                .apply { setPadding(0, ui.dp(10), 0, 0) })
        } else {
            box.addView(ui.text("No fill-ups logged this month. Log them in the Fuel tab to see cost and mileage.", 13.5f, ui.soft))
        }
        box.addView(ui.label("Rides"))
        if (r.rides.isEmpty()) box.addView(ui.text("No rides this month.", 13.5f, ui.soft))
        r.rides.forEachIndexed { i, t ->
            if (i > 0) box.addView(ui.divider())
            box.addView(ui.row(
                ui.column(ui.text((if (t.favourite) "★ " else "") + t.name, 15.5f), ui.text(Fmt.dayTime(t.start), 12.5f, ui.soft)),
                ui.text(Fmt.km(t.distanceKm), 17f).apply { gravity = Gravity.END },
            ).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)); setOnClickListener { openTrip(t) } })
        }
        ui.cardify(box)
    }

    private fun toggle(t: Trip) {
        val s = selected ?: return
        if (!s.add(t.id)) s.remove(t.id)
        if (s.isEmpty()) selected = null
        refresh()
    }

    private fun confirmDelete(all: List<Trip>) {
        val ids = selected ?: return
        val picked = all.filter { it.id in ids }
        if (picked.isEmpty()) return
        val km = picked.sumOf { it.distanceKm }
        android.app.AlertDialog.Builder(a)
            .setTitle(if (picked.size == 1) "Delete this ride?" else "Delete ${picked.size} rides?")
            .setMessage("${Fmt.km(km)} in total. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                picked.forEach { DashHub.store.delete(it) }
                selected = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "↳ Home → Metro Junction Mall · 4.2 km · 14 min" (orange when navigation was stopped before arriving). */
    private fun legRow(l: Leg, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(2), 0, ui.dp(4))
        addView(ui.text("↳", 15f, ui.soft).apply { setPadding(0, 0, ui.dp(8), 0) })
        addView(ui.column(
            ui.text(listOfNotNull(l.from, l.to).joinToString(" → "), 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            ui.text(listOfNotNull(Fmt.km(l.distanceKm), Fmt.dur(l.totalSec),
                when (l.ended) { "arrived" -> null; "cancelled" -> "stopped before arriving"; else -> "ride ended" }).joinToString(" · "),
                12f, if (l.arrived) ui.soft else ui.accent),
        ), LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }

    /** Show one vehicle's rides only (its id), or all (null). */
    private var vehicleFilter: String? = null

    fun update() {
        val vehicles = DashHub.vehicles
        if (!DashHub.showVehicles || vehicles.none { it.id == vehicleFilter }) vehicleFilter = null
        val everything = DashHub.store.trips()
        val all = vehicleFilter?.let { id -> everything.filter { it.vehicle == id } } ?: everything
        val k = "${everything.size}-${everything.firstOrNull()?.id}-${everything.count { it.favourite }}-$period-${DashHub.demo}-${selected?.sorted()}-$report-" +
            "${DashHub.store.fuel().size}-$vehicleFilter-$vehicles"
        if (k == key) return
        key = k
        box.removeAllViews()
        report?.let { (y, m) -> renderReport(all, y, m); return }
        box.addView(ui.text("Trips", 30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        // More than one vehicle: All / each vehicle.
        if (DashHub.showVehicles) {
            val ids = listOf<String?>(null) + vehicles.map { it.id }
            box.addView(android.widget.HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, ui.dp(10), 0, 0)
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    ids.forEach { id ->
                        val on = id == vehicleFilter
                        addView(ui.text(id?.let { DashHub.vehicleName(it) } ?: "All", 13.5f,
                            if (on) android.graphics.Color.WHITE else ui.ink).apply {
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = ui.dpf(16f)
                                if (on) setColor(ui.accent) else { setColor(android.graphics.Color.TRANSPARENT); setStroke(ui.dp(1), ui.line) }
                            }
                            setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) }
                            setOnClickListener { vehicleFilter = id; selected = null; refresh() }
                        })
                    }
                })
            })
        }
        val showVehicle = DashHub.showVehicles && vehicleFilter == null

        if (all.isEmpty()) {
            box.addView(ui.text("Your rides appear here once you've ridden with the bike connected.", 14.5f, ui.soft)
                .apply { setPadding(0, ui.dp(8), 0, 0) })
            return
        }

        box.addView(ui.tabs(listOf("Week", "Month", "All time"), period) { period = it; refresh() }.apply { tag = Ui.SECTION })
        val s = Stats.since(all, period)
        box.addView(ui.space(6))
        box.addView(ui.big("%,.0f".format(Locale.US, s.km), "km", 44f))
        box.addView(ui.statRow(
            "${s.rides}" to if (s.rides == 1) "ride" else "rides",
            Fmt.dur(s.movingSec) to "riding",
            "${s.avgKmh.toInt()}" to "avg km/h",
        ))
        box.addView(ui.statRow(
            "${s.topKmh}" to "top km/h",
            (if (s.rides > 0) Fmt.km(s.km / s.rides) else "—") to "per ride",
            Fmt.km(s.longestKm) to "longest",
        ))
        box.addView(ui.link("Monthly report ›") {
            val c = java.util.Calendar.getInstance()
            report = c.get(java.util.Calendar.YEAR) to c.get(java.util.Calendar.MONTH); refresh()
        }.apply { setPadding(0, ui.dp(14), 0, 0) })

        val list =(all.filter { it.favourite } + all.filterNot { it.favourite }).take(100)
        val sel = selected
        if (sel == null) {
            box.addView(ui.label("Rides"))
        } else {
            // Selection bar: count, select all / none, delete, cancel.
            box.addView(ui.label("${sel.size} selected"))
            val allPicked = list.all { it.id in sel }
            box.addView(ui.row(
                ui.link(if (allPicked) "Select none" else "Select all") {
                    if (allPicked) selected = null else sel.addAll(list.map { it.id })
                    refresh()
                },
                ui.link("Export GPX") { exportSelected(all) }.apply { gravity = Gravity.CENTER },
                ui.link("Delete") { confirmDelete(all) }.apply { gravity = Gravity.END },
            ).apply { setPadding(0, 0, 0, ui.dp(8)) })
            box.addView(ui.link("Cancel") { selected = null; refresh() }.apply { setPadding(0, 0, 0, ui.dp(8)) })
        }
        list.forEachIndexed { i, t ->
            if (i > 0) box.addView(ui.divider())
            val picked = sel?.contains(t.id) == true
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (sel != null) addView(ui.text(if (picked) "●" else "○", 20f, if (picked) ui.accent else ui.soft)
                    .apply { setPadding(0, 0, ui.dp(14), 0) })
                addView(ui.row(
                    ui.column(
                        ui.text((if (t.favourite) "★ " else "") + t.name, 16f),
                        ui.text("${Fmt.dayTime(t.start)} · ${Fmt.dur(t.movingSec)}" +
                            if (showVehicle) " · ${DashHub.vehicleName(t.vehicle)}" else "", 12.5f, ui.soft)
                            .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    ),
                    ui.column(
                        ui.text(Fmt.km(t.distanceKm), 18f).apply { gravity = Gravity.END },
                        ui.text("${t.avgKmh.toInt()} avg · ${t.topKmh} top", 12.5f, ui.soft).apply { gravity = Gravity.END },
                    ),
                ), LinearLayout.LayoutParams(0, -2, 1f))
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                if (picked) setBackgroundColor(ui.line)
                isHapticFeedbackEnabled = false // we buzz ourselves, so it's never doubled or missing
                setOnClickListener { if (selected != null) { buzz(false); toggle(t) } else openTrip(t) }
                setOnLongClickListener {
                    buzz(true)
                    if (selected == null) selected = mutableSetOf()
                    toggle(t); true
                }
            })
            // Sub-trips: the navigations started during this ride, indented under it.
            if (sel == null) t.legs.forEach { l -> box.addView(legRow(l) { openTrip(t) }) }
        }
        if (sel == null && list.size > 1) box.addView(ui.text("Long-press a ride to select several and delete them.", 12f, ui.soft)
            .apply { setPadding(0, ui.dp(14), 0, 0) })
        ui.cardify(box)
    }
}
