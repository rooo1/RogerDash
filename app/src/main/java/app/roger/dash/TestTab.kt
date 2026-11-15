package app.roger.dash

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** Second tab: connect, send test frames, browse dashboard icons, see the raw log. */
class TestTab(private val a: Activity, private val ui: Ui, private val withBluetooth: (() -> Unit) -> Unit) {
    private val state = ui.text("", 20f)
    private val found = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val iconText = ui.text("", 26f)
    private val log: TextView = ui.text("", 11.5f, ui.soft).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
    private val tests = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var foundCount = -1

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(ui.column(
            ui.text("Dash test tools", 30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) },
            ui.cardOf(
                state,
                ui.text("Close Suzuki Ride Connect first (only one app can talk to the dashboard). Ignition on.", 13.5f, ui.soft)
                    .apply { setPadding(0, ui.dp(6), 0, ui.dp(16)) },
                ui.row(
                    ui.primary("Find bike") { withBluetooth { DashService.start(a); DashHub.found.clear(); foundCount = -1; DashHub.link.scan() } },
                    ui.secondary("Disconnect") { DashHub.disconnectBike() },
                ),
                found,
            ),
            tests,
            ui.cardOf(ui.label("Log"), log),
            ui.space(48),
        ).apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), 0) })
    }

    fun update() {
        state.text = DashHub.state
        tests.alpha = if (DashHub.connected) 1f else 0.35f
        if (DashHub.found.size != foundCount) {
            foundCount = DashHub.found.size
            found.removeAllViews()
            DashHub.found.forEach { (d, name) ->
                found.addView(ui.secondary("Connect to $name") { DashHub.link.connect(d) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
                })
            }
        }
        if (log.isShown) log.text = DashHub.logLines.joinToString("\n")
        if (!DashHub.connected) stopAuto()
    }

    private fun eta(min: Int) = Calendar.getInstance().apply { add(Calendar.MINUTE, min) }
    private val link get() = DashHub.link
    private fun rangeKm() = Range.estimate(DashHub.bike?.fuelBars ?: DashHub.lastFuelBars ?: 4, DashHub.store)?.km ?: 0

    private fun buildTests() {
        tests.addView(ui.label("Navigation"))
        tests.addView(ui.text("Arrows repeat 4× a second until you end navigation.", 13.5f, ui.soft))
        tests.addView(ui.space(8))
        tests.addView(ui.row(ui.secondary("⬅ Left 350 m") { link.nav = Frames.nav(Frames.Icon.LEFT, 350, 4200, eta(9)) },
            ui.secondary("Right 1.2 km ➡") { link.nav = Frames.nav(Frames.Icon.RIGHT, 1200, 15300, eta(21)) }))
        tests.addView(ui.space(8))
        tests.addView(ui.row(ui.secondary("⬆ 12.5 km") { link.nav = Frames.nav(Frames.Icon.STRAIGHT, 12500, 48000, eta(52)) },
            ui.secondary("Roundabout 2") { link.nav = Frames.nav(Frames.Icon.ROUNDABOUT_EXIT_1 + 1, 200, 3000, eta(6)) }))
        tests.addView(ui.space(8))
        tests.addView(ui.row(ui.secondary("Arrived") { link.nav = Frames.nav(Frames.Icon.ARRIVE, 0, 0, eta(0), status = '5') },
            ui.secondary("End navigation") { link.nav = null; repeat(10) { link.send(Frames.navEnd()) } }))

        tests.addView(ui.label("Phone"))
        tests.addView(ui.row(ui.secondary("Test call") { repeat(3) { link.send(Frames.call("RogerDash test", ringing = true)) } },
            ui.secondary("Missed call") { repeat(3) { link.send(Frames.missedCall(1, "RogerDash test")) } }))
        tests.addView(ui.space(8))
        tests.addView(ui.row(ui.secondary("Test SMS") { repeat(3) { link.send(Frames.message("RogerDash test", 1)) } },
            ui.secondary("Clear SMS") { repeat(3) { link.send(Frames.message("RogerDash test", 0, isNew = false)) } }))

        tests.addView(ui.label("Range"))
        tests.addView(ui.text("Two ways to show it. Photo both and keep the one that reads better.", 13.5f, ui.soft))
        tests.addView(ui.space(8))
        tests.addView(ui.row(
            ui.secondary("As popup") { DashHub.popOnDash(Range.dashText(rangeKm())) },
            ui.secondary("In nav area") { val m = rangeKm() * 1000; link.nav = Frames.nav(Frames.Icon.STRAIGHT, m, m, eta(0)) },
        ))
        tests.addView(ui.space(8))
        tests.addView(ui.secondary("Low-fuel popup (test)") { DashHub.popOnDash(Range.dashText(35)) })
        tests.addView(ui.space(8))
        tests.addView(ui.row(
            ui.secondary("Street") { DashHub.popOnDash(Dash12.street("Mahatma Gandhi Road")) },
            ui.secondary("10 min") { DashHub.popOnDash("10 MIN LEFT") },
            ui.secondary("Speed") { DashHub.popOnDash("SPEED 92 KMH") },
            gap = 6,
        ))

        tests.addView(ui.label("Icon browser"))
        tests.addView(ui.text("Shows built-in icon N on the dash, with N as the distance (\"00NN m\"). Note which numbers show something new.", 13.5f, ui.soft))
        tests.addView(iconText.apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        tests.addView(ui.row(ui.secondary("−10") { show(icon - 10) }, ui.secondary("−1") { show(icon - 1) },
            ui.secondary("+1") { show(icon + 1) }, ui.secondary("+10") { show(icon + 10) }, gap = 6))
        tests.addView(ui.space(8))
        autoButton = ui.primary("Auto, 3 s each") { toggleAuto() }
        tests.addView(ui.row(autoButton, ui.secondary("Stop") { stopAuto(); link.nav = null; repeat(10) { link.send(Frames.navEnd()) } }))
        ui.cardify(tests)
    }

    // ───────────── icon browser ─────────────

    private lateinit var autoButton: android.widget.Button
    private var icon = 1
    private var autoOn = false
    private val h = Handler(Looper.getMainLooper())
    private val step = object : Runnable {
        override fun run() {
            if (!autoOn) return
            if (icon >= MAX) { stopAuto(); return }
            show(icon + 1)
            h.postDelayed(this, 3000)
        }
    }

    // Last in the class so every field above is initialised first.
    init { buildTests(); updateIcon() }

    private fun updateIcon() { iconText.text = "Icon $icon · ${KNOWN[icon] ?: "unknown"}" }

    private fun show(n: Int) {
        icon = n.coerceIn(0, MAX)
        updateIcon()
        link.nav = Frames.nav(icon, icon, 1000 + icon, eta(10), status = '1')
    }

    private fun toggleAuto() {
        if (autoOn) { stopAuto(); return }
        autoOn = true; autoButton.text = "Pause"
        show(icon); h.postDelayed(step, 3000)
    }

    private fun stopAuto() {
        if (!autoOn) return
        autoOn = false; h.removeCallbacks(step); autoButton.text = "Auto, 3 s each"
    }

    private companion object {
        const val MAX = 99
        val KNOWN = mapOf(
            1 to "left", 2 to "sharp left", 3 to "slight left", 4 to "right", 5 to "sharp right",
            6 to "slight right", 7 to "U-turn left", 8 to "straight", 9 to "arrive (bullseye)", 10 to "off-ramp right",
            11 to "junction left", 12 to "junction right",
            13 to "end of road, left", 14 to "end of road, right", 15 to "head SE", 16 to "head S",
            17 to "head SW", 18 to "head W", 19 to "head NW", 20 to "roundabout exit 1", 21 to "roundabout exit 2",
            22 to "roundabout exit 3", 23 to "roundabout exit 4", 24 to "roundabout exit 5",
            25 to "roundabout exit 6", 26 to "roundabout exit 7+", 27 to "fork keep left", 28 to "fork keep right",
            29 to "fork sharp left", 30 to "fork sharp right", 31 to "continue slight left",
            32 to "continue slight right", 33 to "straight through crossroads", 34 to "curved turn back left",
            35 to "S-curve, bear left?", 36 to "curved turn back right", 37 to "S-curve, bear right?",
            38 to "off-ramp left", 39 to "U-turn right", 40 to "head N", 41 to "head NE", 42 to "head E",
            43 to "ferry", 44 to "off-ramp left", 45 to "enter roundabout", 46 to "! (re-routing / no GPS)",
            61 to "traffic alert",
        )
    }
}
