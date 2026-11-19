package app.roger.dash

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** "How it works": what RogerDash does, which vehicles it knows, what it can't do, and the setup wizard. */
class HelpView(private val a: Activity, private val ui: Ui, private val runSetup: () -> Unit) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24)) })
    }

    init { build() }

    private fun p(s: String, small: Boolean = false) = box.addView(ui.text(s, if (small) 13f else 14.5f, if (small) ui.soft else ui.ink).apply {
        setLineSpacing(ui.dp(2).toFloat(), 1f); setPadding(0, ui.dp(6), 0, 0)
    })

    private fun build() {
        val m = DashHub.model
        box.addView(ui.text("How it works", 30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })

        box.addView(ui.label("Setup"))
        box.addView(ui.link("Run the setup wizard ›") { runSetup() })
        p("Choose your vehicle, allow what it needs, connect to your dash and test turn arrows, calls, messages and spoken turns. " +
            "Ends with a report of what works on this phone.", small = true)

        box.addView(ui.label("Your vehicle"))
        p(m.label)
        p("Vehicles RogerDash knows:", small = true)
        p("•  Suzuki V-Strom 250 SX: tested on a real bike.\n" +
            "•  Other Suzuki with a Bluetooth (Ride Connect) dash: not tested yet. RogerDash uses the same messages as Suzuki's app, " +
            "so it should work; the setup wizard tests it on your bike.\n" +
            "•  Any other bike or scooter: ride log and fuel tracker with the phone's GPS.", small = true)

        if (m.suzuki) {
            box.addView(ui.label("On the dash"))
            p("Switch the ignition on: RogerDash connects over Bluetooth by itself, starts recording the ride, and saves it " +
                "3 minutes after you switch off. Navigate in RogerDash and the turn arrows show on the dash, with spoken turns in your helmet. " +
                "Calls show the caller's name; SMS and WhatsApp show who they're from.")
            p("Only one phone app can talk to the dash at a time, so keep Suzuki Ride Connect closed. " +
                "Pop-ups (range, street names, speed) are ${m.textMax} capital letters, so longer names are shortened. " +
                "The dash never shows message text.", small = true)
        }

        box.addView(ui.label("Fuel and range"))
        p(if (m.suzuki) "Range = fuel bars on the dash × your mileage, counted down by the km you ride. When a bar goes out, the app " +
            "knows the fuel is at the top of the next bar and counts down from there; a full-tank fill-up is an exact starting point too. " +
            "Log full-tank fill-ups on the Fuel tab and the mileage is measured; until then it uses the expected figure (or the AVG you " +
            "typed in from your dash). It's always an estimate: a fuel bar covers a few litres."
        else "Range = tank size × your mileage, minus the km ridden since your last full tank. Log full-tank fill-ups on the Fuel tab " +
            "to start it and to measure your real mileage. Rides you don't record aren't counted.")

        box.addView(ui.label("Privacy"))
        p("No account and no server. Rides, fill-ups and settings stay on your phone. Searches and routes go to OpenStreetMap services, " +
            "or to Google if you entered your own key. Caller and sender names only go to your dash; they aren't stored.", small = true)
        ui.cardify(box)
    }
}
