package app.roger.dash

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Settings, demo mode, better search, backup, help, and the dashboard test tools. */
class MoreTab(
    private val a: Activity, private val ui: Ui, private val openTests: () -> Unit, private val refreshAll: () -> Unit,
    private val openHelp: () -> Unit, private val saveBackup: () -> Unit, private val restoreBackup: () -> Unit,
) {
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    val view: View = ScrollView(a).apply {
        isFillViewport = true
        addView(box.apply { setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24)) })
    }

    init { build() }

    /** After setup: the model and its settings may have changed. */
    fun rebuild() = build()

    private fun build() {
        box.removeAllViews()
        box.addView(ui.text("More", 30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })

        box.addView(ui.label("Help"))
        box.addView(ui.link("How it works ›") { openHelp() })
        box.addView(ui.text("What RogerDash does on your ${DashHub.model.label}, what it can't do, and the setup wizard.", 12.5f, ui.soft))

        box.addView(ui.label("Vehicles"))
        box.addView(ui.text("Your vehicle: ${DashHub.model.label}", 14.5f))
        box.addView(ui.text("To change it, run the setup wizard (How it works, above).", 12.5f, ui.soft).apply { setPadding(0, ui.dp(2), 0, ui.dp(4)) })
        box.addView(ui.toggle("Multiple vehicles", "Add a scooter or another bike; each keeps its own rides, fill-ups, mileage and parked spot",
            DashHub.multiVehicle) { DashHub.multiVehicle = it; build(); refreshAll() })
        if (DashHub.multiVehicle) DashHub.vehicles.forEachIndexed { i, v ->
            box.addView(ui.divider())
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(ui.column(
                    ui.text(v.name + if (DashHub.showVehicles && v.id == DashHub.vehicle.id) "  ·  in use" else "", 15.5f),
                    ui.text((if (v.dash) "Suzuki dash over Bluetooth" else "GPS only") +
                        " · ${Fmt.num(v.tankL)} L tank · expects ${Fmt.num(v.typicalKmpl)} km/l", 12.5f, ui.soft),
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.link("Edit ›") { VehicleUi.edit(a, ui, v) { build(); refreshAll() } }.apply { setPadding(ui.dp(12), 0, 0, 0) })
            })
        }
        if (DashHub.multiVehicle) {
            box.addView(ui.link("＋ Add a scooter or bike") { VehicleUi.edit(a, ui, null) { build(); refreshAll() } })
            box.addView(ui.text(if (DashHub.showVehicles) "Switch vehicle from the top of the Ride or Fuel tab."
                else "Once you add one, a vehicle switch appears at the top of the Ride and Fuel tabs.", 12.5f, ui.soft))
        }

        if (DashHub.model.suzuki) suzuki()
        else box.addView(ui.text("Not a Suzuki: rides are recorded with the phone's GPS. Settings for a Suzuki dash appear once you choose one in setup.",
            12.5f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) })

        navigation()
    }

    private fun suzuki() {
        box.addView(ui.label("Suzuki (Bluetooth)"))
        box.addView(ui.text("Rider name on the dashboard", 12.5f, ui.soft))
        box.addView(ui.field(DashHub.riderName, "Name").apply {
            setOnFocusChangeListener { _, has -> if (!has) DashHub.riderName = text.toString().ifBlank { "Rider" } }
        })
        box.addView(ui.toggle("Record rides automatically", "Starts when the bike connects, saves when it switches off",
            DashHub.autoRide) { DashHub.autoRide = it })
        box.addView(ui.link("Forget this bike") { DashHub.disconnectBike(); DashHub.forgetBike() })

        box.addView(ui.label("Calls and messages"))
        box.addView(ui.toggle("Calls on the dash", "Incoming and missed calls with the caller's name, phone and WhatsApp",
            DashHub.dashCalls) { DashHub.dashCalls = it })
        box.addView(ui.toggle("Messages on the dash", "Who an SMS or WhatsApp message is from. The text is never sent.",
            DashHub.dashMessages) { DashHub.dashMessages = it })
        val phoneOk = PhoneAlerts.hasPhonePermission(a)
        val accessOk = NavListener.allowed(a)
        if (!phoneOk || !accessOk) {
            box.addView(ui.text(listOfNotNull(
                if (!phoneOk) "Phone permission is off: phone calls don't reach the dash." else null,
                if (!accessOk) "Notification access is off: no caller names or messages." else null,
            ).joinToString(" "), 12.5f, ui.accent).apply { setPadding(0, ui.dp(8), 0, 0) })
            box.addView(ui.link("Fix in the setup wizard (How it works) ›") { openHelp() })
        }

        box.addView(ui.label("On the dash"))
        box.addView(ui.text("Short messages that pop up on the dashboard (${DashHub.model.textMax} letters, like an SMS).", 12.5f, ui.soft))
        box.addView(ui.toggle("Range", "\"RANGE 150KM\" after connecting, each time a fuel bar goes out, and if a route is longer than the range",
            DashHub.rangeOnDash) { DashHub.rangeOnDash = it })
        box.addView(ui.toggle("Street names", "Name of the road you turn onto, about 300 m before the turn, e.g. \"M GANDHI RD\"",
            DashHub.dashStreets) { DashHub.dashStreets = it })
        box.addView(ui.toggle("10 minutes left", "\"10 MIN LEFT\" once when you're 10 minutes from your destination (trips over 15 min)",
            DashHub.dashTimeLeft) { DashHub.dashTimeLeft = it })
        val limit = ui.field(DashHub.speedLimit.toString(), "km/h").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { s?.toString()?.toIntOrNull()?.takeIf { it >= 20 }?.let { DashHub.speedLimit = it } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(ui.toggle("Speed warning", "\"SPEED 92 KMH\" when you go over the limit below (at most once a minute)",
            DashHub.dashSpeed) { DashHub.dashSpeed = it })
        box.addView(ui.row(ui.text("Warn above (km/h)", 14.5f, ui.soft), limit))
    }

    /** How to make a key that works with this installed app, with the values to copy. */
    private fun googleSteps() {
        fun step(s: String) = box.addView(ui.text(s, 13f, ui.ink).apply { setPadding(0, ui.dp(8), 0, 0); setLineSpacing(ui.dp(2).toFloat(), 1f) })
        fun copyRow(label: String, value: String) = box.addView(ui.row(
            ui.text("$label\n$value", 12.5f, ui.soft).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) },
            ui.link("Copy") { copy(label, value) }.apply { gravity = android.view.Gravity.END },
        ).apply { (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 4f; setPadding(ui.dp(14), ui.dp(4), 0, 0) })

        box.addView(ui.text("Your own key, step by step", 14.5f).apply { setPadding(0, ui.dp(14), 0, 0) })
        step("1. In Google Cloud Console, create a project. Google asks for a billing account; check Google's current " +
            "Maps pricing (personal use usually stays within the free monthly usage).")
        step("2. Enable two APIs: \"Places API (New)\" (search) and \"Routes API\" (routes).")
        step("3. Create an API key. Under Application restrictions choose Android apps, then add this app:")
        copyRow("Package name", a.packageName)
        copyRow("SHA-1 fingerprint of this installed RogerDash", GoogleAuth.fingerprint(a))
        step("   Under API restrictions, allow only those two APIs.")
        step("4. Paste the key below and tap Save key. Changes in Google Cloud can take a few minutes to work.")
        box.addView(ui.text("The fingerprint is for the RogerDash installed now. A build from somewhere else, or one you build yourself, " +
            "has a different one: add each one you use to the key.", 12.5f, ui.soft).apply { setPadding(0, ui.dp(8), 0, 0) })
        box.addView(ui.text("Google bills your own account. Its free monthly usage covers normal riding; check Google's Maps pricing " +
            "for the current amounts.", 12.5f, ui.soft).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
    }

    /** Tries the saved key with a Google search now, and says how it went (the reason shows above if refused). */
    private fun checkKey() {
        Places.search("petrol pump", DashHub.lastFix) { _ ->
            val ok = GoogleAuth.problem == null
            a.runOnUiThread {
                android.widget.Toast.makeText(a, if (ok) "Key works: Google search and routes are on."
                    else "Google refused the key. See why under Google Maps.", android.widget.Toast.LENGTH_LONG).show()
                build()
            }
        }
    }

    private fun copy(label: String, value: String) {
        a.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        android.widget.Toast.makeText(a, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun navigation() {
        box.addView(ui.label("Navigation"))
        box.addView(ui.toggle("Voice guidance", "Spoken turns in your helmet or earphones (\"In 300 metres, turn left onto MG Road\"). Music gets quieter while it speaks.",
            DashHub.voiceOn) { DashHub.voiceOn = it; if (it) Voice.say("Voice guidance on") })
        box.addView(ui.toggle("Avoid tolls", "Also switchable on each route before you start", DashHub.avoidTolls) { DashHub.avoidTolls = it })
        box.addView(ui.toggle("Avoid highways", "Smaller roads, usually slower", DashHub.avoidHighways) { DashHub.avoidHighways = it })
        box.addView(ui.toggle("Avoid unpaved roads", "Skip dirt and gravel roads", DashHub.avoidUnpaved) { DashHub.avoidUnpaved = it })
        box.addView(ui.text("Home and Work: set them on the Navigate tab, then they're one tap away.", 12.5f, ui.soft)
            .apply { setPadding(0, ui.dp(6), 0, 0) })

        box.addView(ui.label("Google Maps"))
        val problem = GoogleAuth.problem
        box.addView(ui.text(when {
            DashHub.googleKey.isBlank() -> "Not set up. Places and routes come from OpenStreetMap (free), which misses many newer shops and malls. " +
                "With your own Google key you get Google search, two-wheeler routes with live traffic, and petrol pumps along the route. " +
                "Your searches then go to Google."
            problem != null -> "Google refused your key, so search and routes are using OpenStreetMap.\nGoogle said: $problem\n" +
                "Usually the key doesn't allow this app yet: check step 3 below."
            else -> "✓ Your key is saved. Google search, routes and petrol pumps use it; if Google refuses it, this says why."
        }, 13.5f, when { DashHub.googleKey.isBlank() -> ui.soft; problem != null -> ui.bad; else -> ui.ok }))
        // The map is Google's only in a build with a map key inside (not the public download); the key above is for search and routes.
        if (RouteMap.googleMapKey(a)) box.addView(ui.toggle("Google's map",
            "This build has Google's map. Off: the free OpenStreetMap map. Applies when a map opens next.",
            DashHub.googleMap) { DashHub.googleMap = it })
        else box.addView(ui.text("The map is the free OpenStreetMap map; the key is for search and routes.", 12.5f, ui.soft)
            .apply { setPadding(0, ui.dp(8), 0, 0) })
        googleSteps()
        val key = ui.field(DashHub.googleKey, "Google Maps API key (optional)").apply {
            // Hidden like a password: the key isn't shown on screen (or in screenshots).
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
        }
        box.addView(key)
        box.addView(ui.row(
            ui.link("Google Cloud Console ›") {
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/google/maps-apis/credentials"))) }
            },
            ui.link("Save key") {
                GoogleAuth.worked() // a new key gets a fresh chance
                DashHub.googleKey = key.text.toString()
                key.clearFocus()
                refreshAll()
                if (DashHub.googleKey.isBlank()) {
                    android.widget.Toast.makeText(a, "Key removed: OpenStreetMap search and routes", android.widget.Toast.LENGTH_SHORT).show()
                    build(); return@link
                }
                android.widget.Toast.makeText(a, "Checking the key with Google…", android.widget.Toast.LENGTH_SHORT).show()
                checkKey()
            }.apply { gravity = android.view.Gravity.END },
        ))

        box.addView(ui.label("Try it"))
        box.addView(ui.toggle("Demo mode", "Made-up rides and a live bike, to see the app. Your real rides aren't touched.",
            DashHub.demo) { on -> if (on) DashHub.startDemo() else DashHub.stopDemo(); refreshAll() })

        box.addView(ui.label("Backup"))
        box.addView(ui.text("Rides, fill-ups, vehicles, places and settings in one file, for a new phone or a reinstall. " +
            "It includes your Google key if you entered one, so keep the file private.", 12.5f, ui.soft))
        box.addView(ui.row(
            ui.link("Save a backup ›") { saveBackup() },
            ui.link("Restore ›") { restoreBackup() }.apply { gravity = android.view.Gravity.END },
        ))

        box.addView(ui.label(if (DashHub.model.suzuki) "Dashboard" else "Log"))
        box.addView(ui.link(if (DashHub.model.suzuki) "Dash test tools ›" else "App log ›") { openTests() })
        box.addView(ui.text(if (DashHub.model.suzuki) "Connect manually, send test arrows, calls and messages, browse the dashboard's icons, see the log."
            else "What the app has been doing, for when something doesn't work.", 12.5f, ui.soft))
        ui.cardify(box)
    }
}
