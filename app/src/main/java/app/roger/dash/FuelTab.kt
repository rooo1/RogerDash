package app.roger.dash

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/** Range from the fuel bars, real mileage and cost from fill-ups, and the fill-up log. */
class FuelTab(private val a: Activity, private val ui: Ui) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var key = ""

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24)) })
    }

    fun refresh() { key = ""; update() }

    fun update() {
        val v = DashHub.vehicle
        val bars = DashHub.bike?.takeIf { DashHub.bikeFresh }?.fuelBars ?: DashHub.lastFuelBars
        val log = DashHub.store.fuel(v.id)
        val k = "${v}-$bars-${log.size}-${DashHub.store.trips().size}-${DashHub.demo}-${DashHub.connected}-${DashHub.bikeEconomy}"
        if (k == key) return
        key = k
        box.removeAllViews()
        box.addView(ui.text("Fuel", 30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        VehicleUi.switcher(a, ui) { refresh() }?.let {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
            box.addView(it)
        }

        // Range: always an estimate (fuel bars × mileage, or km since the last full tank), with how much to trust it.
        box.addView(ui.label("Estimated range"))
        val riding = DashHub.ride?.takeIf { it.vehicle == v.id }?.distanceKm ?: 0.0
        val est = Range.now(DashHub.store, v, bars, riding)
        val e = Economy.of(log)
        if (est != null) {
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(ui.big("~${est.km}", "km", 52f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(FuelBars(a, ui).apply { this.bars = if (v.dash) bars else Range.barsOf(est, v) },
                    LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(16) })
            })
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(6), 0, ui.dp(8))
                addView(confidenceChip(est.confidence))
                addView(ui.text("  ± ${est.plusMinusKm} km", 13f, ui.soft))
            })
            val live = DashHub.bikeFresh || DashHub.demo
            box.addView(ui.text(
                if (v.dash) "Estimate: $bars of ${v.bars.takeIf { it > 0 } ?: Range.BARS} bars (${if (live) "live" else "last sync " + Fmt.ago(DashHub.lastBikeTime)}) " +
                    "× %.1f km/l, ${est.basis}. Not a measurement: a fuel bar covers about %s L."
                        .format(Locale.US, est.kmPerL, Fmt.num(v.tankL / (v.bars.takeIf { it > 0 } ?: Range.BARS)))
                else "Estimate: ${Fmt.num(v.tankL)} L tank × %.1f km/l, minus the km since your ${est.basis}. ".format(Locale.US, est.kmPerL) +
                    "Rides you didn't record aren't counted.", 13f, ui.soft))
            e.kmPerL?.takeIf { Range.plausible(it) }?.let {
                box.addView(ui.text("Actual, measured: a full tank (${Fmt.num(v.tankL)} L) lasts about %,d km at your %.1f km/l."
                    .format(Locale.US, (it * v.tankL).toInt(), it), 13f, ui.ink).apply { setPadding(0, ui.dp(8), 0, 0) })
            }
        } else {
            box.addView(ui.text(if (v.dash) "Connect the bike once and its fuel level appears here."
                else "Fill the tank to full and log it below: range then counts down with every ride you record.", 14.5f, ui.soft))
        }
        box.addView(ui.toggle("Low fuel reminder", "Phone notification (and voice) when range drops under ${Range.LOW_KM} km",
            DashHub.lowFuelReminder) { DashHub.lowFuelReminder = it })

        // Mileage measured tank to tank
        box.addView(ui.label("Actual mileage · full-tank method"))
        if (e.kmPerL != null) {
            box.addView(ui.big("%.1f".format(Locale.US, e.kmPerL), "km/l", 44f))
            box.addView(ui.text("Measured over ${e.intervals} full tank${if (e.intervals > 1) "s" else ""}, %,d km.".format(Locale.US, e.kmMeasured.toInt()),
                13f, ui.soft))
        } else {
            box.addView(ui.text(
                when {
                    log.none { it.full } -> "Fill the tank to full and log it. At the next full fill-up the app measures your real mileage: " +
                        "e.g. full at 4,500 km, full again at 4,650 km with 4.5 L → 150 ÷ 4.5 = 33.3 km/l."
                    else -> "Fill up to full next time and log it: the km since your last full tank ÷ the litres gives your real mileage."
                }, 14f, ui.soft))
        }
        // The dash's own average, typed in (used for range until fill-ups are measured twice)
        val typed = DashHub.dashAvg
        if (v.dash) box.addView(ui.row(
            ui.text(typed?.let { "Dash average: %.1f km/l (typed in)".format(Locale.US, it) } ?: "Dash average: not set", 14f, ui.soft),
            ui.link(if (typed != null) "Change ›" else "Enter ›") { enterDashAvg() }.apply { gravity = Gravity.END },
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        else if (e.kmPerL == null) box.addView(ui.text("Until then: %.1f km/l, the figure you expect (change it in the vehicle's settings)."
            .format(Locale.US, v.typicalKmpl), 13f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) })
        box.addView(ui.space(12))
        box.addView(ui.primary("Log fill-up") { addFuel() })

        // Money
        if (log.isNotEmpty()) {
            box.addView(ui.label("Fuel expense"))
            val months = Economy.monthly(log)
            box.addView(ui.statRow(
                "₹%,.0f".format(Locale.US, months.last().second) to "this month",
                (e.costPerKm?.let { "₹%.2f".format(Locale.US, it) } ?: "—") to "per km",
                "₹%,.0f".format(Locale.US, e.cost) to "all time",
            ))
            Economy.pricePerL(log)?.let {
                box.addView(ui.text("Last price ₹%.2f / L · %.1f L filled in all".format(Locale.US, it, e.litres), 12.5f, ui.soft)
                    .apply { setPadding(0, ui.dp(8), 0, ui.dp(10)) })
            }
            if (months.any { it.second > 0 }) {
                val mf = java.text.SimpleDateFormat("MMM", Locale.getDefault())
                box.addView(ui.text("Spent per month", 13f, ui.soft).apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
                box.addView(BarChart(a, ui, months.map { it.second }, months.map { mf.format(java.util.Date(it.first)) },
                    { "₹%,.0f".format(Locale.US, it) }))
            }
        }

        // Mileage over time: each measured full tank
        val sortedLog = log.sortedBy { it.odo }
        val measured = sortedLog.indices.mapNotNull { i -> Economy.at(sortedLog, i)?.let { sortedLog[i] to it } }.takeLast(8)
        if (measured.size >= 2) {
            box.addView(ui.label("Mileage over time"))
            val df = java.text.SimpleDateFormat("d/M", Locale.getDefault())
            box.addView(BarChart(a, ui, measured.map { it.second }, measured.map { df.format(java.util.Date(it.first.time)) },
                { "%.1f".format(Locale.US, it) }))
            val first = measured.first().second; val last = measured.last().second
            box.addView(ui.text(when {
                last > first * 1.05 -> "Improving: %.1f → %.1f km/l since the first measured tank.".format(Locale.US, first, last)
                last < first * 0.95 -> "Dropping: %.1f → %.1f km/l. Tyre pressure, chain, or more city riding?".format(Locale.US, first, last)
                else -> "Steady around %.1f km/l. Tap a bar for its value.".format(Locale.US, measured.map { it.second }.average())
            }, 13f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        // History
        if (log.isNotEmpty()) {
            box.addView(ui.label("Fuel history"))
            val sorted = log.sortedBy { it.odo }
            sorted.asReversed().forEachIndexed { n, f ->
                val kmpl = Economy.at(log, sorted.indexOf(f))
                if (n > 0) box.addView(ui.divider())
                box.addView(ui.row(
                    ui.column(ui.text(Fmt.day(f.time), 15f), ui.text("%,d km · ${if (f.full) "full tank" else "top-up"}".format(Locale.US, f.odo), 12.5f, ui.soft)),
                    ui.column(
                        ui.text("%.2f L · ₹%,.0f".format(Locale.US, f.litres, f.cost), 15f).apply { gravity = Gravity.END },
                        ui.text(when {
                            kmpl != null -> "%.1f km/l".format(Locale.US, kmpl)
                            !f.full -> "counted at next full tank"
                            else -> "start of measuring"
                        }, 12.5f, if (kmpl != null) ui.ink else ui.soft).apply { gravity = Gravity.END },
                    ),
                ).apply { setOnLongClickListener { confirmDelete(f); true } })
            }
            box.addView(ui.text("Long-press a fill-up to delete it.", 12f, ui.soft).apply { setPadding(0, ui.dp(12), 0, 0) })
        }
        ui.cardify(box)
    }

    /** "● High confidence" pill: green / amber / grey. */
    private fun confidenceChip(c: Range.Confidence) = ui.status("${c.label} confidence", when (c) {
        Range.Confidence.HIGH -> ui.ok
        Range.Confidence.MEDIUM -> android.graphics.Color.parseColor("#F29900")
        Range.Confidence.LOW -> ui.soft
    }).apply {
        (background as? android.graphics.drawable.GradientDrawable)?.setColor(ui.bg)
        setOnClickListener {
            AlertDialog.Builder(a).setTitle("How sure is this range?").setMessage(
                "High: mileage measured over 2 or more full tanks.\n\nMedium: your dash's average typed in, or 1 measured full tank.\n\n" +
                    "Low: a typical figure, or fuel bars used on rides.\n\nAlways an estimate either way: the dash shows fuel in 6 bars of about 2 L, " +
                    "so the range can be off by half a bar.").setPositiveButton("OK", null).show()
        }
    }

    private fun addFuel() {
        fun field(hint: String, value: String = "") = EditText(a).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val v = DashHub.vehicle
        // The Suzuki sends its odometer; for anything else the rider reads it off the vehicle.
        val odo = if (v.dash) DashHub.bike?.odoKm ?: DashHub.lastOdo else null
        val litres = field("Litres")
        val cost = field("Cost (₹)")
        val km = field("Odometer (km)", odo?.toString() ?: "")
        val full = android.widget.CheckBox(a).apply {
            text = "Filled to full (tank brim)"; isChecked = true; setTextColor(ui.ink)
            buttonTintList = ui.tint(ui.accent)
        }
        AlertDialog.Builder(a)
            .setTitle("Log fill-up · ${v.name}")
            .setMessage((if (odo != null) "Odometer filled in from your bike. " else "Type the odometer reading from your ${v.name}. ") +
                "Full tanks give the real mileage: km since the last full tank ÷ litres.")
            .setView(ui.column(litres, cost, km, full).apply { setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) })
            .setPositiveButton("Save") { _, _ ->
                val l = litres.text.toString().toDoubleOrNull()
                val o = km.text.toString().toIntOrNull()
                if (l != null && l > 0 && o != null) {
                    DashHub.store.addFuel(FuelEntry(System.currentTimeMillis(), l, cost.text.toString().toDoubleOrNull() ?: 0.0, o, full.isChecked, v.id))
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The rider reads AVG (km/l) off the dash's trip A or B screen and types it in; range uses it. */
    private fun enterDashAvg() {
        val f = EditText(a).apply {
            hint = "km/l, e.g. 32.5"
            DashHub.dashAvg?.let { setText("%.1f".format(Locale.US, it)) }
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(a)
            .setTitle("Average from your dash")
            .setMessage("Show trip A or B on the dash and type its AVG figure. Range uses it. Trip B (longer) is usually the steadier number.")
            .setView(ui.column(f).apply { setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) })
            .setPositiveButton("Save") { _, _ ->
                f.text.toString().toDoubleOrNull()?.takeIf { Range.plausible(it) }?.let { DashHub.dashAvg = it; refresh() }
            }
            .setNeutralButton("Clear") { _, _ -> DashHub.dashAvg = null; refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(f: FuelEntry) {
        AlertDialog.Builder(a).setMessage("Delete the fill-up of ${Fmt.day(f.time)}?")
            .setPositiveButton("Delete") { _, _ -> DashHub.store.deleteFuel(f); refresh() }
            .setNegativeButton("Cancel", null).show()
    }
}
