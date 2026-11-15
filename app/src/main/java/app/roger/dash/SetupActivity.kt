package app.roger.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * First-run setup. The rider picks their vehicle (a Suzuki model, another Suzuki, or not a Suzuki), and
 * every later step follows that choice: permissions, Location, the dash link with a real arrow, pop-up,
 * call and message on the dash, spoken turns, the vehicle's figures, battery settings. Ends with a
 * report of what works on this phone and bike, and what the dash or Android doesn't allow.
 *
 * The tests go through the app's own code ([DashHub], [DashLink], [Frames], [PhoneAlerts], [Voice],
 * [LocationOn]), so a pass here means the real feature works. Dash test results are written to the
 * app log, so riders with untested models can share what they saw.
 *
 * Opens with the app until finished or skipped; can be run again from More → How it works.
 */
@SuppressLint("MissingPermission")
class SetupActivity : Activity() {

    private enum class Step(val title: String) {
        VEHICLE("Your vehicle"),
        PERMISSIONS("Allow access"),
        LOCATION("Location"),
        DASH("Connect to your dash"),
        CALLS("Calls and messages"),
        VOICE("Spoken turns"),
        DETAILS("Your vehicle's details"),
        BATTERY("Screen-off recording"),
        REPORT("Your setup"),
    }

    private enum class Res { PASS, WARN, FAIL, SKIP, INFO }

    private class Question(val prompt: String, val answers: List<Pair<String, () -> Unit>>)

    private lateinit var ui: Ui
    private var step = Step.VEHICLE
    private var question: Question? = null

    // Results of the tests that need the rider to look or listen
    private var arrowRes = Res.SKIP
    private var popupRes = Res.SKIP
    private var popupNote = ""
    private var callRes = Res.SKIP
    private var messageRes = Res.SKIP
    private var voiceRes = Res.SKIP
    private var detailsSaved = false

    // Views
    private lateinit var scroll: ScrollView
    private lateinit var stepLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout
    private lateinit var backBtn: Button
    private lateinit var nextBtn: Button

    private val model get() = DashHub.model

    private val steps: List<Step>
        get() = if (model.suzuki) Step.values().toList() else Step.values().filter { it != Step.DASH && it != Step.CALLS }

    /** Live connection state on the dash steps; elsewhere nothing on screen depends on it. */
    private val hubListener: () -> Unit = {
        if ((step == Step.DASH || step == Step.CALLS || step == Step.LOCATION) && question == null) render()
    }

    // ───────────── lifecycle ─────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        DashHub.init(this)
        if (DashHub.demo) DashHub.stopDemo() // the tests need the real bike
        ui = Ui(this)
        savedInstanceState?.getString("step")?.let { s -> Step.values().firstOrNull { it.name == s }?.let { step = it } }
        buildUi()
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("step", step.name)
    }

    override fun onStart() {
        super.onStart()
        DashHub.addListener(hubListener)
    }

    override fun onStop() {
        DashHub.removeListener(hubListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        PhoneAlerts.start(this) // Phone permission may have been allowed in Settings meanwhile
        if (question == null) render() // back from Settings
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PhoneAlerts.start(this)
        DashHub.warmPosition()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOCATION_ON && resultCode == RESULT_OK) DashHub.warmPosition()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    // ───────────── navigation between steps ─────────────

    private fun next() {
        val i = steps.indexOf(step)
        if (i < steps.size - 1) goTo(steps[i + 1])
    }

    private fun goBack() {
        if (question != null) { question = null; render(); return }
        val i = steps.indexOf(step)
        if (i <= 0) finish() else goTo(steps[i - 1])
    }

    private fun goTo(s: Step) {
        endDashTest()
        step = s
        question = null
        if (s == Step.LOCATION) DashHub.warmPosition() // a fresh fix shows the GPS really works
        render()
        scroll.scrollTo(0, 0)
    }

    private fun finishSetup() {
        endDashTest()
        DashHub.setupDone = true
        setResult(RESULT_OK)
        finish()
    }

    // ───────────── checks ─────────────

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun locationAllowed() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun precise() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun bluetoothAllowed() = granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notificationsAllowed() = granted(Manifest.permission.POST_NOTIFICATIONS)
    private fun phoneAllowed() = granted(Manifest.permission.READ_PHONE_STATE)
    private fun notificationAccess() = NavListener.allowed(this)
    private fun btOn() = runCatching { getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun suzukiAppInstalled() = runCatching { packageManager.getPackageInfo(SUZUKI_APP, 0); true }.getOrDefault(false)

    /** Runtime permissions this setup asks for. Phone only while calls on the dash are switched on. */
    private fun wanted(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (model.suzuki) {
            add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            if (DashHub.dashCalls) add(Manifest.permission.READ_PHONE_STATE)
        }
    }

    private fun missing() = wanted().filterNot { granted(it) }

    /** Where Android sends spoken turns now: a Bluetooth helmet/earphones, or the phone itself. */
    private fun audioOut(): String? {
        val am = getSystemService(AudioManager::class.java)
        val bt = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in bt }?.productName?.toString()
    }

    // ───────────── layout ─────────────

    private fun dp(v: Int) = ui.dp(v)

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        stepLabel = ui.text("", 12f, ui.accent, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.1f }
        val skip = ui.text("Skip setup", 14f, ui.soft).apply {
            setPadding(dp(16), dp(12), 0, dp(12))
            setOnClickListener { finishSetup() }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(skip)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ui.tint(ui.accent)
            progressBackgroundTintList = ui.tint(ui.line)
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4) }
        }
        titleView = ui.text("", 28f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, dp(18), 0, dp(2))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(progress)
        col.addView(titleView)
        col.addView(body)

        backBtn = ui.secondary("Back") { goBack() }
        nextBtn = ui.primary("Next") {}
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.bg)
            setPadding(dp(20), dp(10), dp(20), dp(16))
            addView(backBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(nextBtn, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(10) })
        }
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            fitsSystemWindows = true
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        })
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
    }

    private fun para(s: String, small: Boolean = false, color: Int = if (small) ui.soft else ui.ink) {
        body.addView(ui.text(s, if (small) 13.5f else 15.5f, color).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun link(s: String, onClick: () -> Unit) {
        body.addView(ui.link(s, onClick).apply { minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL })
    }

    private fun section(s: String) = body.addView(ui.label(s).apply { setPadding(0, dp(24), 0, dp(2)) })

    private fun color(r: Res) = when (r) {
        Res.PASS -> ui.ok
        Res.WARN -> ui.warn
        Res.FAIL -> ui.bad
        Res.SKIP -> ui.soft
        Res.INFO -> Color.parseColor("#1A73E8")
    }

    private fun statusRow(res: Res, title: String, detail: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(12), 0, 0)
        addView(ui.text(when (res) { Res.PASS -> "✓"; Res.WARN -> "!"; Res.FAIL -> "✕"; Res.SKIP -> "–"; Res.INFO -> "i" },
            13f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(res)) }
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(title, 15.5f))
            if (!detail.isNullOrEmpty()) addView(ui.text(detail, 13.5f, ui.soft).apply { setPadding(0, dp(2), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun row(res: Res, title: String, detail: String? = null) = body.addView(statusRow(res, title, detail))

    private fun primary(label: String, enabled: Boolean = true, action: () -> Unit) {
        nextBtn.text = label
        nextBtn.isEnabled = enabled && question == null
        nextBtn.alpha = if (nextBtn.isEnabled) 1f else 0.4f
        nextBtn.setOnClickListener { action() }
    }

    private fun testButton(label: String, enabled: Boolean, onClick: () -> Unit) {
        body.addView(ui.secondary(label, onClick).apply {
            isEnabled = enabled && question == null
            alpha = if (isEnabled) 1f else 0.4f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        })
    }

    private fun questionCard(q: Question): View = ui.cardOf(ui.text(q.prompt, 17f)).apply {
        q.answers.forEachIndexed { i, (label, act) ->
            addView((if (i == 0) ui.primary(label, act) else ui.secondary(label, act)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            })
        }
    }

    private fun ask(prompt: String, vararg answers: Pair<String, () -> Unit>) {
        question = Question(prompt, answers.map { (label, act) -> label to { act(); question = null; render() } })
        render()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openAppSettings() = runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun openNotificationAccess() = runCatching {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NavListener::class.java).flattenToString()))
    }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }

    // ───────────── steps ─────────────

    private fun render() {
        if (isFinishing || isDestroyed) return
        if (step !in steps) step = Step.DETAILS
        val i = steps.indexOf(step).coerceAtLeast(0)
        stepLabel.text = "STEP ${i + 1} OF ${steps.size}"
        progress.progress = (i + 1) * 100 / steps.size
        titleView.text = step.title
        body.removeAllViews()
        when (step) {
            Step.VEHICLE -> renderVehicle()
            Step.PERMISSIONS -> renderPermissions()
            Step.LOCATION -> renderLocation()
            Step.DASH -> renderDash()
            Step.CALLS -> renderCalls()
            Step.VOICE -> renderVoice()
            Step.DETAILS -> renderDetails()
            Step.BATTERY -> renderBattery()
            Step.REPORT -> renderReport()
        }
        question?.let { body.addView(questionCard(it)) }
        backBtn.visibility = if (i == 0) View.INVISIBLE else View.VISIBLE
    }

    private fun renderVehicle() {
        para("RogerDash shows turn arrows, calls and messages on Suzuki dashboards that connect over Bluetooth, " +
            "and keeps a ride log and fuel tracker for any bike or scooter. Setup takes about three minutes.")
        para("What do you ride?", color = ui.ink)
        for (m in DashModel.values()) body.addView(modelCard(m))
        if (model.suzuki && !model.tested) para("RogerDash uses the same Bluetooth messages as Suzuki's app, so it should work, " +
            "but nobody has tried it on this model yet. The dash test in step ${steps.indexOf(Step.DASH) + 1} checks your bike, " +
            "and its results go in the app's log so you can share them.", small = true, color = ui.warn)
        primary("Next") { next() }
    }

    private fun modelCard(m: DashModel): View {
        val on = m == model
        val sub = when (m) {
            DashModel.VSTROM_250_SX -> "Tested on a real bike: turn arrows, pop-ups, calls, messages and fuel range on the dash."
            DashModel.OTHER_SUZUKI -> "Any other Suzuki bike or scooter with a Bluetooth (Ride Connect) dash. Not tested yet."
            DashModel.NONE -> "Any bike or scooter: ride log and fuel tracker with the phone's GPS. No Bluetooth needed."
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = ui.dpf(18f)
                setColor(ui.card)
                setStroke(dp(if (on) 2 else 1), if (on) ui.accent else ui.line)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            addView(ui.text((if (on) "●  " else "○  ") + m.title, 16.5f, if (on) ui.ink else ui.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
            addView(ui.text(sub, 13.5f, ui.soft).apply { setPadding(0, dp(4), 0, 0) })
            if (m.suzuki && !m.tested) addView(ui.text("NOT TESTED YET", 11f, ui.warn, Typeface.create("sans-serif-medium", Typeface.NORMAL))
                .apply { letterSpacing = 0.1f; setPadding(0, dp(6), 0, 0) })
            setOnClickListener {
                if (m != model) {
                    DashHub.setModel(m)
                    detailsSaved = false
                    arrowRes = Res.SKIP; popupRes = Res.SKIP; callRes = Res.SKIP; messageRes = Res.SKIP
                }
                render()
            }
        }
    }

    private fun renderPermissions() {
        val suzuki = model.suzuki
        para("RogerDash asks only for what your setup uses:")
        row(if (locationAllowed()) Res.PASS else Res.FAIL, "Location",
            "Maps of your rides, navigation and places near you. Your position stays on the phone (and goes to the route search).")
        row(if (notificationsAllowed()) Res.PASS else Res.WARN, "Notifications",
            "The notification that keeps recording with the screen off, and low-fuel reminders.")
        if (suzuki) {
            row(if (bluetoothAllowed()) Res.PASS else Res.FAIL, "Nearby devices (Bluetooth)", "To find and talk to your dash.")
            if (DashHub.dashCalls) row(if (phoneAllowed()) Res.PASS else Res.WARN, "Phone",
                "To know when your phone rings, so the call shows on the dash. RogerDash never makes calls or reads your call history.")
            row(if (notificationAccess()) Res.PASS else Res.WARN, "Notification access",
                "Who is calling, and who an SMS or WhatsApp message is from (never the message itself), for the dash. " +
                    "Other notifications are ignored.")
        }
        val miss = missing()
        if (suzuki && !notificationAccess()) {
            link("Open notification access ›") { openNotificationAccess() }
            para("If Android says \"Restricted setting\", open App info → ⋮ (top right) → Allow restricted settings, then try again. " +
                "Android does this for apps installed from a file.", small = true)
        }
        if (miss.isNotEmpty()) {
            para("If Android stops asking, allow them in app settings.", small = true)
            link("Open app settings ›") { openAppSettings() }
            primary("Allow") { requestPermissions(miss.toTypedArray(), 1) }
        } else primary("Next") { next() }
        if (miss.isNotEmpty()) link("Continue without them ›") { next() }
    }

    private fun renderLocation() {
        val on = LocationOn.isOn(this)
        val fix = DashHub.lastFix
        para("GPS draws your rides on the map and guides you. " +
            if (model.suzuki) "Without it, rides still record distance and fuel from the bike." else "Without it, rides can't be recorded.")
        row(if (locationAllowed()) Res.PASS else Res.FAIL, "Location allowed", if (locationAllowed()) null else "Go back one step and allow it.")
        row(if (precise()) Res.PASS else if (locationAllowed()) Res.WARN else Res.SKIP, "Precise location",
            if (precise()) null else "Navigation needs it: App settings → Permissions → Location → Use precise location.")
        row(if (on) Res.PASS else Res.FAIL, "Location switched on")
        row(if (fix != null) Res.PASS else Res.SKIP,
            when { fix != null -> "Your position was found"; on && locationAllowed() -> "Looking for your position…"; else -> "Your position" },
            when {
                fix != null -> "%.4f, %.4f".format(Locale.US, fix.lat, fix.lon)
                on && locationAllowed() -> "Outdoors this takes a few seconds."
                else -> "Switch Location on to check it."
            })
        if (!precise() && locationAllowed()) link("Open app settings ›") { openAppSettings() }
        if (!on) primary("Turn on location") { LocationOn.ask(this, REQ_LOCATION_ON) { render() } }
        else primary("Next") { next() }
        if (!on) link("Continue without it ›") { next() }
    }

    // ───────────── the dash ─────────────

    private fun eta(min: Int) = Calendar.getInstance().apply { add(Calendar.MINUTE, min) }

    private fun endDashTest() {
        if (DashHub.connected && !DashHub.demo && DashHub.nav == null && DashHub.link.nav != null) {
            DashHub.link.nav = null
            repeat(10) { DashHub.link.send(Frames.navEnd()) }
        }
    }

    /** Dash test results go in the app log (More → Dash test tools → Log), most useful for untested models. */
    private fun logResult(test: String, res: Res, note: String = "") {
        DashHub.onLog("Setup dash test: model=${model.title}${if (!model.tested) " (not tested yet)" else ""}, " +
            "dash=${DashHub.bikeName ?: "?"}, $test=${res.name.lowercase()}${if (note.isNotEmpty()) " ($note)" else ""}")
    }

    private fun renderDash() {
        val connected = DashHub.connected
        val name = DashHub.bikeName
        para("Switch the ignition on and keep the phone near the bike.")
        if (suzukiAppInstalled()) para("Suzuki Ride Connect is installed. Only one app can talk to the dash at a time, so close it first: " +
            "turn off its notification access (it restarts itself otherwise) and Force stop it.", small = true, color = ui.warn)
        row(if (bluetoothAllowed()) Res.PASS else Res.FAIL, "Bluetooth allowed", if (bluetoothAllowed()) null else "Go back to step 2.")
        row(if (btOn()) Res.PASS else Res.FAIL, "Bluetooth on")
        row(if (connected) Res.PASS else Res.SKIP,
            if (connected) "Connected to your dash" else DashHub.state,
            when {
                connected -> name
                DashHub.state.startsWith("Another app") -> "Close Suzuki Ride Connect (see above), then try again."
                DashHub.state.contains("doesn't look like") -> "This device isn't a Suzuki dash RogerDash knows."
                else -> null
            })
        if (DashModel.legacyDash(name)) row(Res.FAIL, "Older Access/Burgman dash",
            "Its name ($name) shows an older dash that uses a different checksum. RogerDash doesn't support it yet.")
        if (!btOn()) {
            testButton("Turn on Bluetooth", bluetoothAllowed()) {
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT) }
                    .onFailure { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        } else if (!connected) {
            testButton(if (DashHub.bikeAddress != null) "Connect to my bike" else "Find my bike", bluetoothAllowed()) {
                DashService.start(this); DashHub.connectBike(); render()
            }
        }

        section("Test on the dash")
        row(arrowRes, "Turn arrow", if (arrowRes == Res.SKIP) "Not tested yet" else if (arrowRes == Res.PASS) null else "The dash didn't show it.")
        testButton(if (arrowRes == Res.SKIP) "Send a test arrow" else "Send it again", connected) {
            DashHub.link.nav = Frames.nav(Frames.Icon.RIGHT, 350, 4200, eta(9))
            ask("Does the dash show a right-turn arrow and 350 m?",
                "Yes" to { arrowRes = Res.PASS; logResult("arrow", arrowRes); endDashTest() },
                "No" to { arrowRes = Res.FAIL; logResult("arrow", arrowRes); endDashTest() })
        }
        row(popupRes, "Pop-up message", when (popupRes) {
            Res.SKIP -> "Not tested yet"; Res.PASS -> null; else -> popupNote
        })
        testButton(if (popupRes == Res.SKIP) "Send a test pop-up" else "Send it again", connected) {
            DashHub.popOnDash(POPUP)
            ask("Does the dash show \"$POPUP\"?",
                "Yes, all of it" to { popupRes = Res.PASS; popupNote = ""; logResult("popup", popupRes) },
                "Yes, but cut short" to { popupRes = Res.WARN; popupNote = "Part of it was cut off. Range and street pop-ups may be shortened."; logResult("popup", popupRes, "cut short") },
                "No" to { popupRes = Res.FAIL; popupNote = "Range, street-name and speed pop-ups won't show."; logResult("popup", popupRes) })
        }
        primary(if (connected || arrowRes != Res.SKIP) "Next" else "Skip for now") { next() }
    }

    private fun renderCalls() {
        val connected = DashHub.connected
        para("When your phone rings, the caller's name shows on the dash. For SMS and WhatsApp, the dash shows who it's from, " +
            "never the message.")
        body.addView(ui.toggle("Calls on the dash", "Incoming and missed calls, phone and WhatsApp", DashHub.dashCalls) {
            DashHub.dashCalls = it; render()
        })
        body.addView(ui.toggle("Messages on the dash", "Who an SMS or WhatsApp message is from", DashHub.dashMessages) {
            DashHub.dashMessages = it; render()
        })
        if (DashHub.dashCalls) row(if (phoneAllowed()) Res.PASS else Res.WARN, "Phone permission",
            if (phoneAllowed()) "Phone calls reach the dash." else "Without it, phone calls don't show (WhatsApp calls still do).")
        if (DashHub.dashCalls || DashHub.dashMessages) row(if (notificationAccess()) Res.PASS else Res.WARN, "Notification access",
            if (notificationAccess()) "Caller names, SMS and WhatsApp reach the dash." else "Without it, calls show without a name and messages don't show.")
        if (!phoneAllowed() && DashHub.dashCalls) link("Allow Phone ›") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2) }
        if (!notificationAccess() && (DashHub.dashCalls || DashHub.dashMessages)) link("Open notification access ›") { openNotificationAccess() }

        section("Test on the dash")
        if (!connected) para("Connect to your dash in the previous step to send these.", small = true)
        row(callRes, "Incoming call", if (callRes == Res.SKIP) "Not tested yet" else if (callRes == Res.PASS) null else "The dash didn't show it.")
        testButton(if (callRes == Res.SKIP) "Send a test call" else "Send it again", connected) {
            PhoneAlerts.testCall()
            ask("Does the dash show an incoming call from \"RogerDash test\"?",
                "Yes" to { callRes = Res.PASS; logResult("call", callRes) },
                "No" to { callRes = Res.FAIL; logResult("call", callRes) })
        }
        row(messageRes, "Message", if (messageRes == Res.SKIP) "Not tested yet" else if (messageRes == Res.PASS) null else "The dash didn't show it.")
        testButton(if (messageRes == Res.SKIP) "Send a test message" else "Send it again", connected) {
            PhoneAlerts.testMessage()
            ask("Does the dash show a message from \"ROGERDASH\"?",
                "Yes" to { messageRes = Res.PASS; logResult("message", messageRes); PhoneAlerts.clearTests() },
                "No" to { messageRes = Res.FAIL; logResult("message", messageRes); PhoneAlerts.clearTests() })
        }
        if (connected) para("For a real test, ask someone to call or text you now.", small = true)
        primary("Next") { next() }
    }

    private fun renderVoice() {
        val out = audioOut()
        para("RogerDash speaks the turns (\"In 300 metres, turn left onto MG Road\") through your helmet intercom or earphones. " +
            "Music gets quieter while it speaks.")
        row(if (Voice.available) Res.PASS else Res.WARN, "Text-to-speech on this phone",
            if (Voice.available) null else "No English voice is ready. Install one in Settings → Text-to-speech, or turn spoken turns off.")
        row(if (out != null) Res.PASS else Res.INFO, if (out != null) "Plays through $out" else "Plays through the phone's speaker",
            if (out != null) null else "Connect your helmet intercom or earphones first, then play the test.")
        row(voiceRes, "Heard it", when (voiceRes) { Res.SKIP -> "Not tested yet"; Res.FAIL -> "Turn up the media volume, and check the intercom is connected for music."; else -> null })
        body.addView(ui.toggle("Spoken turns", "You can change this later in More", DashHub.voiceOn) { DashHub.voiceOn = it; render() })
        testButton(if (voiceRes == Res.SKIP) "Play a test" else "Play it again", Voice.available) {
            Voice.say("This is RogerDash. In 300 metres, turn left.")
            ask("Did you hear \"This is RogerDash\"${if (out != null) " in $out" else ""}?",
                "Yes" to { voiceRes = Res.PASS },
                "No" to { voiceRes = Res.FAIL })
        }
        primary("Next") { next() }
    }

    private fun renderDetails() {
        val v = DashHub.vehicles.firstOrNull { it.id == Vehicle.MAIN } ?: Vehicle.defaultFor(model)
        para(when {
            model.tankL != null -> "Filled in for the ${model.label}. Change anything that's different on yours."
            else -> "Range is worked out from these until your full-tank fill-ups measure the real mileage. " +
                "The tank size is in your owner's manual."
        })
        fun num(value: Double, hint: String) = ui.field("%.1f".format(Locale.US, value).removeSuffix(".0"), hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        fun caption(s: String) = body.addView(ui.text(s, 12.5f, ui.soft).apply { setPadding(0, dp(16), 0, 0) })
        caption("Name")
        val name = ui.field(v.name, "e.g. My Gixxer").also { body.addView(it) }
        caption("Tank size, litres")
        val tank = num(v.tankL, "litres").also { body.addView(it) }
        caption("Mileage you expect, km/l")
        val kmpl = num(v.typicalKmpl, "km/l").also { body.addView(it) }
        var rider: EditText? = null
        var avg: EditText? = null
        if (model.suzuki) {
            caption("Your name on the dash's welcome screen")
            rider = ui.field(DashHub.riderName, "Rider").also { body.addView(it) }
            caption("Trip average from your dash (AVG, km/l), if it shows one. Optional")
            avg = num(DashHub.dashAvg ?: 0.0, "e.g. 32.8").apply { if (DashHub.dashAvg == null) setText("") }.also { body.addView(it) }
        }
        if (model.suzuki) {
            val b = DashHub.lastBike
            row(if (b != null) Res.PASS else Res.INFO, "Odometer and fuel",
                if (b != null) "From your dash: ${b.odoKm?.let { "%,d km".format(Locale.US, it) } ?: "—"}, " +
                    "${b.fuelBars ?: "—"} of ${v.bars.takeIf { it > 0 } ?: Range.BARS} fuel bars. They update by themselves."
                else "They come from your dash by themselves once it connects.")
        } else row(Res.INFO, "Range", "Log a full tank on the Fuel tab; range counts down from there as you ride.")
        if (detailsSaved) row(Res.PASS, "Saved")
        primary("Save and continue") {
            val t = tank.text.toString().toDoubleOrNull()?.takeIf { it in 1.0..40.0 }
            val k = kmpl.text.toString().toDoubleOrNull()?.takeIf { Range.plausible(it) }
            if (t == null) { tank.error = "Between 1 and 40 litres"; return@primary }
            if (k == null) { kmpl.error = "Between 10 and 80 km/l"; return@primary }
            DashHub.saveVehicle(v.copy(name = name.text.toString().trim().ifBlank { v.name }, tankL = t, typicalKmpl = k))
            rider?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { DashHub.riderName = it }
            avg?.text?.toString()?.toDoubleOrNull().let { a -> DashHub.dashAvg = a?.takeIf { Range.plausible(it) } }
            detailsSaved = true
            next()
        }
    }

    private fun renderBattery() {
        val free = batteryFree()
        para("RogerDash records rides and stays connected to the dash in a background service with a notification, " +
            "so Android normally lets it run with the screen off. Some phones (Samsung included) still put apps to sleep " +
            "to save battery, which can cut a ride short.")
        row(if (free) Res.PASS else Res.WARN, if (free) "Battery: unrestricted" else "Battery: optimised",
            if (free) null else "Optional, but safer for long rides: App settings → Battery → Unrestricted.")
        if (!free) link("Open app settings ›") { openAppSettings() }
        primary("Next") { next() }
    }

    private fun renderReport() {
        val suzuki = model.suzuki
        val rows = ArrayList<Triple<Res, String, String?>>()
        rows += Triple(Res.INFO, "Vehicle: ${model.label}", if (suzuki && !model.tested) "Not tested with RogerDash yet." else null)
        rows += Triple(if (locationAllowed() && LocationOn.isOn(this)) (if (precise()) Res.PASS else Res.WARN) else Res.FAIL,
            "Location for ride maps and navigation", when {
                !locationAllowed() -> "Not allowed."
                !LocationOn.isOn(this) -> "Switched off."
                !precise() -> "Approximate only: navigation needs Precise."
                else -> null
            })
        rows += Triple(if (notificationsAllowed()) Res.PASS else Res.WARN, "Notifications", if (notificationsAllowed()) null else "No recording notification or fuel reminders.")
        if (suzuki) {
            rows += Triple(if (DashHub.connected) Res.PASS else if (DashHub.bikeAddress != null) Res.INFO else Res.WARN,
                "Dash connection", when {
                    DashHub.connected -> "Connected to ${DashHub.bikeName ?: "your dash"}."
                    DashHub.bikeAddress != null -> "Paired with ${DashHub.bikeName ?: "your dash"}. Connects when the ignition is on."
                    else -> "Not connected yet. Ride tab → Find bike, with the ignition on."
                })
            rows += Triple(arrowRes, "Turn arrows on the dash", note(arrowRes))
            rows += Triple(popupRes, "Pop-ups (range, street names, speed)", if (popupRes == Res.WARN || popupRes == Res.FAIL) popupNote else note(popupRes))
            rows += Triple(if (!DashHub.dashCalls) Res.SKIP else worst(callRes, if (phoneAllowed()) Res.PASS else Res.WARN),
                "Calls on the dash", when {
                    !DashHub.dashCalls -> "Switched off."
                    !phoneAllowed() -> "Phone permission missing: phone calls won't show."
                    !notificationAccess() -> "Calls show without the caller's name (notification access is off)."
                    else -> note(callRes)
                })
            rows += Triple(if (!DashHub.dashMessages) Res.SKIP else worst(messageRes, if (notificationAccess()) Res.PASS else Res.WARN),
                "SMS and WhatsApp on the dash", when {
                    !DashHub.dashMessages -> "Switched off."
                    !notificationAccess() -> "Notification access is off: messages won't show."
                    else -> note(messageRes)
                })
        }
        rows += Triple(if (!DashHub.voiceOn) Res.SKIP else if (!Voice.available) Res.WARN else voiceRes, "Spoken turns",
            if (!DashHub.voiceOn) "Switched off." else if (!Voice.available) "No text-to-speech voice." else note(voiceRes))
        rows += when {
            DashHub.googleKey.isBlank() -> Triple(Res.INFO, "Search and routes: OpenStreetMap",
                "Free, but misses some newer places. For Google search and routes, add your own key: More → Google Maps has the steps.")
            GoogleAuth.problem != null -> Triple(Res.WARN, "Google refused your key",
                "Search and routes use OpenStreetMap until it's fixed. More → Google Maps shows this app's package name and fingerprint to add to the key.")
            else -> Triple(Res.INFO, "Search and routes: your Google key", "Checked the first time you search.")
        }
        rows += Triple(if (batteryFree()) Res.PASS else Res.WARN, "Screen-off recording",
            if (batteryFree()) null else "Battery is optimised: set it to Unrestricted for long rides.")

        val fails = rows.count { it.first == Res.FAIL }
        val warns = rows.count { it.first == Res.WARN || it.first == Res.SKIP }
        para(when {
            fails > 0 -> "A few things need fixing before you ride. They're marked ✕ below."
            warns > 0 -> "RogerDash is ready. A few things are worth a look."
            else -> "Everything works on this phone${if (suzuki) " and bike" else ""}. Ride safe!"
        })
        if (suzuki && !model.tested) {
            body.addView(statusRow(Res.WARN, "Your model hasn't been tested with RogerDash yet",
                "The dash test results are in the log (More → Dash test tools → Log). Please share them, with your model and year, " +
                    "so the next rider knows what works."))
        }
        section("On this phone (Android ${Build.VERSION.RELEASE})")
        rows.forEach { (r, t, d) -> row(r, t, d) }

        section(if (suzuki) "What the dash or Android doesn't allow" else "What Android doesn't allow")
        if (suzuki) {
            row(Res.INFO, "One app at a time", "Only one phone app can talk to the dash, so Suzuki Ride Connect must stay closed while RogerDash is used.")
            row(Res.INFO, "Short pop-ups", "The ${if (model.tested) "${model.label} " else ""}dash shows ${model.textMax} capital letters in a pop-up, so street names are shortened " +
                "(\"Mahatma Gandhi Road\" → \"M GANDHI RD\")${if (!model.tested) ". That's the V-Strom 250 SX's limit; yours may differ" else ""}.")
            row(Res.INFO, "No message text on the dash", "The dash only takes who a message is from and how many there are, never the text.")
            if (model == DashModel.VSTROM_250_SX) row(Res.INFO, "No trip average from the bike",
                "The V-Strom doesn't send its AVG km/l, so range uses your fill-ups, or the average you typed in.")
            row(Res.INFO, "Older Access and Burgman Street dashes", "Dashes named \"SAS…\" use a different checksum; RogerDash doesn't support them yet.")
        }
        row(Res.INFO, "Notification access for apps installed from a file",
            "Android may block it at first (\"Restricted setting\"). Allow it in App info → ⋮ → Allow restricted settings.")

        link("Run the tests again") { goTo(if (suzuki) Step.DASH else Step.VOICE) }
        primary("Done") { finishSetup() }
    }

    private fun note(r: Res) = when (r) { Res.SKIP -> "Not tested"; Res.FAIL -> "Didn't show in the test."; else -> null }

    private fun worst(a: Res, b: Res): Res {
        val order = listOf(Res.PASS, Res.INFO, Res.SKIP, Res.WARN, Res.FAIL)
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    private companion object {
        const val REQ_LOCATION_ON = 5
        const val REQ_BT = 4
        /** 12 characters: fills the V-Strom's pop-up exactly, so a shorter dash shows it cut. */
        const val POPUP = "ROGERDASH OK"
    }
}
