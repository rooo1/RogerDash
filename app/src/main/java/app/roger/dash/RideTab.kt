package app.roger.dash

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/**
 * Home dashboard: connection, odometer, trips & mileage, fuel, start recording, last ride, parked spot.
 * Bike numbers always say whether they are live or from the last sync.
 */
class RideTab(
    private val a: Activity,
    private val ui: Ui,
    private val goNavigate: () -> Unit,
    private val goFuel: () -> Unit,
    private val needLocation: () -> Unit,
    /** Start button: makes sure Location is on (asks), then starts the ride. */
    private val startRecording: () -> Unit,
    /** Turns Bluetooth on if needed (asks), starts the service, connects now. */
    private val connect: () -> Unit,
    private val openTrip: (Trip) -> Unit,
) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var key = ""

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)) })
    }

    fun refresh() { key = ""; update() }

    private fun btOn() = runCatching { a.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)

    fun update() {
        val r = DashHub.ride
        val b = DashHub.bike.takeIf { DashHub.bikeFresh }
        val n = DashHub.nav
        val v = DashHub.vehicle
        val k = listOf(v, DashHub.connected, DashHub.state, DashHub.demo, btOn(), r?.distanceKm?.let { "%.1f".format(it) }, r?.elapsedSec?.div(5),
            r?.topKmh, b, n?.icon, n?.toTurnM, n?.instruction, DashHub.lastFuelBars, DashHub.store.trips().firstOrNull()?.id,
            DashHub.store.fuel().size, (System.currentTimeMillis() - DashHub.lastBikeTime) / 60_000).joinToString()
        if (k == key) return
        key = k
        box.removeAllViews()

        VehicleUi.switcher(a, ui) { refresh() }?.let { box.addView(it) }
        if (v.dash) connection() else gpsOnlyLine()
        if (r != null) {
            val suzukiRide = r.vehicle == Range.dashBike().id
            riding(r, b.takeIf { suzukiRide }, suzukiRide)
            n?.let { nextTurn(it) }
            if (suzukiRide) fuel(b, own = true) else gpsFuel(v, r.distanceKm, own = true)
        } else {
            if (v.dash) bikeNow(b) else gpsVehicle(v) // main numbers and fuel in one card
            n?.let { nextTurn(it) }
            quickStart()
        }
        recent(showParked = r == null && (!v.dash || !DashHub.connected), v)
        box.addView(ui.space(8).apply { tag = Ui.END })
        ui.cardify(box)
    }

    // ───────────── connection ─────────────

    private fun lastSync() = if (DashHub.lastBikeTime == 0L) "No data from the bike yet" else "Last sync ${Fmt.ago(DashHub.lastBikeTime)}"

    /** Clear state plus what to do about it: connected (live), Bluetooth off, looking, another app has it, never paired. */
    private fun connection() {
        val amber = Color.parseColor("#F29900")
        val red = Color.parseColor("#D93025")
        val (title, dot, sub) = when {
            DashHub.demo -> Triple("Demo · made-up data", amber, "Turn demo off in More")
            DashHub.connected -> Triple(DashHub.shownBikeName?.let { "Connected · $it" } ?: "Connected", ui.ok, "Live data from the bike")
            !btOn() -> Triple("Bluetooth is off", red, "${lastSync()} · turn it on to connect")
            DashHub.bikeAddress == null -> Triple("No bike paired yet", ui.soft, "Switch the ignition on and keep the phone close.")
            DashHub.state.startsWith("Another app") -> Triple("Another app has the bike", red, lastSync())
            DashService.running -> Triple("Looking for your bike…", amber, "${lastSync()} · connects when the ignition is on")
            else -> Triple("Disconnected", ui.soft, lastSync())
        }
        // Plain lines at the top of the page (not a card): state + action, and one small line of context.
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.dot(dot).apply { (layoutParams as LinearLayout.LayoutParams).apply { width = ui.dp(10); height = ui.dp(10) } })
            addView(ui.text(title, 17f, ui.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!DashHub.connected && !DashHub.demo) addView(ui.link(when {
                !btOn() -> "Turn on ›"
                DashHub.bikeAddress == null -> "Find bike ›"
                else -> "Connect ›"
            }) { connect() }.apply { setPadding(0, 0, 0, 0) })
        })
        box.addView(ui.text(sub, 12.5f, ui.soft).apply { setPadding(ui.dp(18), ui.dp(2), 0, ui.dp(4)) })
        if (!DashHub.connected && !DashHub.demo && DashHub.state.startsWith("Another app")) {
            box.addView(ui.text("Suzuki Ride Connect grabs the bike first. Its notification access restarts it even after " +
                "Force stop, so: 1) turn off its notification access, 2) Force stop it, 3) don't open it while you use this app.", 13.5f, ui.accent)
                .apply { setPadding(0, ui.dp(10), 0, 0) })
            box.addView(ui.link("1. Notification access ›") {
                runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
            box.addView(ui.link("2. Force stop Suzuki Ride Connect ›") {
                runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", SUZUKI_APP, null))) }
            })
        }
    }

    /** GPS-only vehicle: no Bluetooth to show, just how it records. */
    private fun gpsOnlyLine() {
        box.addView(ui.text("Recorded with your phone's GPS. Your Suzuki takes over by itself when it connects.", 12.5f, ui.soft)
            .apply { setPadding(ui.dp(4), 0, 0, ui.dp(4)) })
    }

    /** GPS-only vehicle: this month's riding, mileage, and range from the km since the last full tank. */
    private fun gpsVehicle(v: Vehicle) {
        val s = Stats.since(DashHub.store.trips(v.id), Stats.MONTH)
        box.addView(headed("This month", null))
        box.addView(ui.big("%,.0f".format(Locale.US, s.km), "km", 44f))
        box.addView(ui.statRow(
            "${s.rides}" to if (s.rides == 1) "ride" else "rides",
            Fmt.dur(s.movingSec) to "riding",
            "%.1f".format(Locale.US, Range.mileageOf(DashHub.store, v).kmPerL) to "km/l",
        ))
        gpsFuel(v, 0.0, own = false)
    }

    /** Range of a GPS-only vehicle, drawn on the same gauge (fuel left as sixths of the tank). */
    private fun gpsFuel(v: Vehicle, ridingKm: Double, own: Boolean) {
        if (own) box.addView(headed("Fuel", null)) else box.addView(ui.divider())
        val est = Range.fromLog(DashHub.store, v, ridingKm)
        if (est == null) {
            box.addView(ui.link("Log a full tank on the Fuel tab to see the range ›") { goFuel() }.apply { setPadding(0, ui.dp(12), 0, 0) })
            return
        }
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, 0)
            addView(ui.column(
                ui.big("~${est.km}", "km", 30f),
                ui.text("estimated range", 12f, ui.soft).apply { setPadding(0, ui.dp(2), 0, 0) },
            ), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.column(
                FuelBars(a, ui).apply { bars = Range.barsOf(est, v) },
                ui.text("~%.1f L left".format(Locale.US, est.km / est.kmPerL), 12f, ui.soft).apply { gravity = Gravity.END; setPadding(0, ui.dp(4), 0, 0) },
            ))
            setOnClickListener { goFuel() }
        })
        confidenceRow(est, "since last full tank")
    }

    private fun confidenceRow(est: Range.Estimate, basis: String) {
        val c = when (est.confidence) {
            Range.Confidence.HIGH -> ui.ok
            Range.Confidence.MEDIUM -> Color.parseColor("#F29900")
            Range.Confidence.LOW -> ui.soft
        }
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.dot(c))
            addView(ui.text("${est.confidence.label} confidence · ± ${est.plusMinusKm} km · $basis",
                12.5f, ui.soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { goFuel() }
        })
        if (est.km < Range.LOW_KM) box.addView(ui.text("Fuel low: refuel soon.", 13.5f, Color.parseColor("#D93025")).apply { setPadding(0, ui.dp(6), 0, 0) })
    }

    /** "LIVE" (green) or "LAST SYNC 2 h AGO" (grey): so old bike data is never mistaken for live data. */
    private fun freshness(live: Boolean): View = ui.text(
        if (live) "● LIVE" else if (DashHub.lastBikeTime == 0L) "NO DATA YET" else "LAST SYNC ${Fmt.ago(DashHub.lastBikeTime).uppercase()}",
        10.5f, if (live) Color.WHITE else ui.soft, Typeface.create("sans-serif-medium", Typeface.NORMAL),
    ).apply {
        letterSpacing = 0.08f
        setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
        background = GradientDrawable().apply {
            cornerRadius = ui.dpf(10f)
            if (live) setColor(ui.ok) else { setColor(Color.TRANSPARENT); setStroke(ui.dp(1), ui.line) }
        }
    }

    /** Section label with the live / last-sync badge on the right ([live] null: no badge). */
    private fun headed(label: String, live: Boolean?) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, ui.dp(6))
        addView(ui.label(label).apply { setPadding(0, 0, 0, 0); tag = null }, LinearLayout.LayoutParams(0, -2, 1f))
        live?.let { addView(freshness(it)) }
        tag = Ui.SECTION
    }

    // ───────────── bike ─────────────

    /** Odometer first, then trip A, trip B and mileage, small. Live while connected, else the last sync. */
    private fun bikeNow(live: Frames.BikeData?) {
        val d = live ?: DashHub.lastBike.takeIf { !DashHub.demo } ?: return fuel(live, own = true)
        val isLive = live != null
        box.addView(headed("Your bike", isLive))
        box.addView(ui.big(d.odoKm?.let { "%,d".format(Locale.US, it) } ?: "—", "km", 44f))
        val kmpl = Range.mileageOf(DashHub.store)
        box.addView(ui.statRow(
            (d.tripAKm?.let { "%.1f".format(Locale.US, it) } ?: "—") to "trip A km",
            (d.tripBKm?.let { "%.1f".format(Locale.US, it) } ?: "—") to "trip B km",
            "%.1f".format(Locale.US, kmpl.kmPerL) to "km/l",
        ))
        live?.let { b ->
            val extra = listOfNotNull(b.speedKmh?.takeIf { it > 0 }?.let { "$it" to "km/h" }, DashHub.engineTemp(b)?.let { "$it °C" to "engine" })
            if (extra.isNotEmpty()) box.addView(ui.statRow(*extra.toTypedArray()))
        }
        fuel(live, own = false)
    }

    private fun riding(r: RideRecorder, b: Frames.BikeData?, suzuki: Boolean) {
        box.addView(ui.text("Riding · recording", 13f, ui.accent, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { tag = Ui.SECTION })
        box.addView(ui.big("%.1f".format(Locale.US, r.distanceKm), "km", 60f))
        val avg = if (r.movingSec > 30) r.distanceKm / (r.movingSec / 3600.0) else 0.0
        box.addView(ui.statRow(Fmt.clock(r.elapsedSec) to "time", "${avg.toInt()}" to "avg km/h", "${r.topKmh}" to "top km/h"))
        box.addView(ui.space(16))
        box.addView(ui.secondary("Finish ride") { DashHub.stopRide() })

        // The bike's numbers, live, or the last ones if the connection dropped mid-ride.
        if (!suzuki) return
        val shown = b ?: DashHub.lastBike.takeIf { !DashHub.demo } ?: return
        box.addView(headed("Bike", b != null))
        box.addView(ui.statRow(*listOfNotNull(
            shown.speedKmh?.takeIf { b != null }?.let { s -> "$s" to "km/h now" },
            shown.tripAKm?.let { t -> "%.1f".format(Locale.US, t) to "trip A km" },
            shown.odoKm?.let { o -> "%,d".format(Locale.US, o) to "odometer" },
        ).toTypedArray()).apply { setPadding(0, 0, 0, 0) })
        DashHub.engineTemp(b)?.let { box.addView(ui.statRow("$it °C" to "engine")) }
        if (b == null) box.addView(ui.text("Bike disconnected: these are its last numbers. The ride keeps recording with GPS.", 12.5f, ui.accent)
            .apply { setPadding(0, ui.dp(8), 0, 0) })
    }

    // ───────────── fuel ─────────────

    /**
     * Gauge, estimated range with ±, and how sure it is. Tap for the Fuel tab.
     * [own]: in its own card with a badge; otherwise it continues the bike card under a divider.
     */
    private fun fuel(b: Frames.BikeData?, own: Boolean) {
        val bars = b?.fuelBars ?: DashHub.lastFuelBars ?: return
        val est = Range.estimate(bars, DashHub.store) ?: return
        if (own) box.addView(headed("Fuel", b != null)) else box.addView(ui.divider())
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, 0)
            addView(ui.column(
                ui.big("~${est.km}", "km", 30f),
                ui.text("estimated range", 12f, ui.soft).apply { setPadding(0, ui.dp(2), 0, 0) },
            ), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.column(
                FuelBars(a, ui).apply { this.bars = bars },
                ui.text("$bars / ${Range.dashBike().bars.takeIf { it > 0 } ?: Range.BARS} bars", 12f, ui.soft).apply { gravity = Gravity.END; setPadding(0, ui.dp(4), 0, 0) },
            ))
            setOnClickListener { goFuel() }
        })
        confidenceRow(est, shortBasis(est.basis))
    }

    private fun shortBasis(basis: String) = when {
        basis.startsWith("measured") -> "measured"
        basis.contains("dash") -> "from dash average"
        basis.startsWith("expected") -> "expected mileage"
        else -> "from rides"
    }

    // ───────────── actions & history ─────────────

    /** On the page between the cards (tagged END: not inside a card). */
    private fun quickStart() {
        box.addView(ui.primary("● Start recording") { startRecording() }.apply {
            tag = Ui.END
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) }
        })
    }

    /** Where this vehicle was parked (where its last ride ended), as one compact row. */
    private fun recent(showParked: Boolean, v: Vehicle) {
        val p = (if (showParked) DashHub.store.parked(v.id) else null) ?: return
        box.addView(compactRow("Parked", "${Fmt.ago(p.time)} · tap for the map") {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode("My " + v.name)})")))
        }.apply { tag = Ui.SECTION })
    }

    private fun compactRow(title: String, sub: String, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(8), 0, ui.dp(4))
        addView(ui.column(
            ui.text(title, 15.5f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            ui.text(sub, 12.5f, ui.soft).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(2), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.text("›", 22f, ui.accent).apply { setPadding(ui.dp(12), 0, 0, 0) })
        setOnClickListener { onClick() }
    }

    /** Compact next-turn banner; tap for the map. */
    private fun nextTurn(n: NavStep) {
        box.addView(ui.label("Next turn"))
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(Arrows.of(n.icon), 40f, ui.accent).apply { setPadding(0, 0, ui.dp(16), 0) })
            addView(ui.column(
                ui.text(if (n.status == '2') "Re-routing…" else Arrows.dist(n.toTurnM), 26f).apply {
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                },
                ui.text(n.instruction, 14f, ui.soft).apply { maxLines = 2 },
            ), LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { goNavigate() }
        })
    }

}

const val SUZUKI_APP = "suzuki.com.suzuki"

/** Phone-screen arrows and distances shared by the tabs. */
object Arrows {
    fun of(icon: Int) = when (icon) {
        Frames.Icon.LEFT, Frames.Icon.END_OF_ROAD_LEFT -> "↰"
        Frames.Icon.RIGHT, Frames.Icon.END_OF_ROAD_RIGHT -> "↱"
        Frames.Icon.SHARP_LEFT -> "⮢"
        Frames.Icon.SHARP_RIGHT -> "⮣"
        Frames.Icon.SLIGHT_LEFT, Frames.Icon.KEEP_LEFT -> "↖"
        Frames.Icon.SLIGHT_RIGHT, Frames.Icon.KEEP_RIGHT, Frames.Icon.OFF_RAMP_RIGHT -> "↗"
        Frames.Icon.U_TURN -> "↶"
        Frames.Icon.U_TURN_RIGHT -> "↷"
        Frames.Icon.OFF_RAMP_LEFT, Frames.Icon.JUNCTION_LEFT -> "↖"
        Frames.Icon.JUNCTION_RIGHT -> "↗"
        Frames.Icon.ENTER_ROUNDABOUT -> "↻"
        Frames.Icon.FERRY -> "⛴"
        Frames.Icon.REROUTING -> "!"
        Frames.Icon.ARRIVE -> "⚑"
        in Frames.Icon.ROUNDABOUT_EXIT_1..Frames.Icon.ROUNDABOUT_EXIT_1 + 6 -> "↻"
        else -> "↑"
    }

    fun dist(m: Int?) = m?.let { if (it < 1000) "$it m" else "%.1f km".format(Locale.US, it / 1000.0) } ?: ""
}
