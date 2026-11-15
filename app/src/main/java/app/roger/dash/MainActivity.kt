package app.roger.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Five tabs, each about one screen: Ride (home), Navigate, Trips, Fuel, More.
 * Ride details and the dash test tools open on top, with Back to return.
 */
class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var ride: RideTab
    private lateinit var nav: NavTab
    private lateinit var trips: TripsTab
    private lateinit var fuel: FuelTab
    private lateinit var more: MoreTab
    private lateinit var tests: TestTab
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var tab = 0
    private var overlay: View? = null
    private val listener: () -> Unit = { onChange() }

    private val names = listOf("Ride", "Navigate", "Trips", "Fuel", "More")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        DashHub.init(this)
        ui = Ui(this)
        ride = RideTab(this, ui, goNavigate = { showTab(1) }, goFuel = { showTab(3) }, needLocation = { askLocation() }, startRecording = { startRecording() }, connect = { connectNow() },
            openTrip = { openTrip(it) })
        nav = NavTab(this, ui, requireLocation = { then -> requireLocation(then) })
        trips = TripsTab(this, ui) { openTrip(it) }
        fuel = FuelTab(this, ui)
        tests = TestTab(this, ui, withBluetooth = { then -> withBluetooth(then) })
        more = MoreTab(this, ui, openTests = { openOverlay(tests.view) }, refreshAll = { refreshAll() },
            openHelp = { openOverlay(HelpView(this, ui) { openSetup() }.view) },
            saveBackup = { saveBackup() }, restoreBackup = { pickBackup() })

        content = FrameLayout(this)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.card) // a raised bar, a step lighter than the page
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            fitsSystemWindows = true
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tabBar)
        })
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.card
        // Screen rebuilt (theme/size change, or Android recreating it): stay on the same tab.
        showTab(savedInstanceState?.getInt("tab", 0) ?: 0)

        // First run: the setup wizard asks for permissions and tests the dash; otherwise ask for what's missing.
        if (!DashHub.setupDone) {
            if (savedInstanceState == null) openSetup()
            return
        }
        val perms = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
        connectKnownBike()
    }

    /** What the chosen setup uses: Bluetooth and Phone only with a Suzuki (Phone only while calls go to the dash). */
    private fun neededPermissions() = buildList {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (DashHub.model.suzuki) {
            add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            if (DashHub.dashCalls) add(Manifest.permission.READ_PHONE_STATE)
        }
    }

    /** A known bike: connect in the background straight away. */
    private fun connectKnownBike() {
        val bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (DashHub.model.suzuki && DashHub.bikeAddress != null && !DashService.running && bt) {
            DashService.start(this); DashHub.connectBike()
        }
    }

    /** The setup wizard is open on top (until its result comes back). */
    private var setupOpen = false

    @Suppress("DEPRECATION")
    private fun openSetup() {
        setupOpen = true
        startActivityForResult(Intent(this, SetupActivity::class.java), REQ_SETUP)
    }

    // ───────────── backup and restore ─────────────

    @Suppress("DEPRECATION")
    private fun saveBackup() {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "RogerDash-backup-$day.json"), REQ_BACKUP)
        }.onFailure { toast("No file picker on this phone") }
    }

    @Suppress("DEPRECATION")
    private fun pickBackup() {
        if (DashHub.ride != null) { toast("Finish the ride being recorded first"); return }
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE)
        }.onFailure { toast("No file picker on this phone") }
    }

    private fun writeBackup(uri: android.net.Uri) = runCatching {
        val json = Backup.export(this)
        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
        DashHub.onLog("Backup saved (${DashHub.store.trips().size} rides)")
        toast("Backup saved")
    }.onFailure { toast("Couldn't save the backup: ${it.message}") }

    private fun readBackup(uri: android.net.Uri) {
        val r = runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Backup.read(text)
        }
        val (o, s) = r.getOrElse { toast(it.message ?: "Couldn't read that file"); return }
        val made = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(s.made))
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            .setMessage("Made $made: ${s.rides} ${if (s.rides == 1) "ride" else "rides"}, ${s.vehicles} " +
                "${if (s.vehicles == 1) "vehicle" else "vehicles"}${if (s.hasFuel) ", fill-ups" else ""}, places and settings.\n\n" +
                "It replaces the rides, fill-ups and settings on this phone.")
            .setPositiveButton("Restore") { _, _ ->
                runCatching { Backup.restore(this, o) }
                    .onSuccess {
                        DashHub.reloadStore()
                        DashHub.onLog("Backup restored (${s.rides} rides)")
                        toast("Restored")
                        recreate()
                    }
                    .onFailure { toast("Couldn't restore: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        DashHub.addListener(listener)
        DashHub.warmPosition() // so search finds places near you
        // During setup its Location step asks; straight after it, the rider has just answered.
        if (DashHub.setupDone && !setupOpen) ensureLocationOn()
        refreshAll()
    }

    override fun onStop() {
        DashHub.removeListener(listener)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            tab == 2 && trips.onBack() -> {}
            tab == 1 && nav.onBack() -> {}
            tab != 0 -> showTab(0)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun onChange() {
        if (overlay === tests.view) { tests.update(); return }
        if (overlay != null) return
        when (tab) {
            0 -> ride.update(); 1 -> nav.update(); 2 -> trips.update(); 3 -> fuel.update()
        }
    }

    private fun refreshAll() {
        ride.refresh(); nav.refresh(); trips.refresh(); fuel.refresh()
        onChange()
    }

    private fun showTab(i: Int) {
        tab = i
        overlay = null
        content.removeAllViews()
        content.addView(when (i) { 0 -> ride.view; 1 -> nav.view; 2 -> trips.view; 3 -> fuel.view; else -> more.view })
        tabBar.removeAllViews()
        names.forEachIndexed { n, name ->
            val on = n == i
            // Icon in a pill (tinted when selected), label under it: the Android bottom-bar pattern.
            val pill = android.widget.FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ui.dpf(16f)
                    setColor(if (on) android.graphics.Color.argb(46, 232, 89, 12) else android.graphics.Color.TRANSPARENT)
                }
                addView(TabIcon(this@MainActivity, ui, n).apply { color = if (on) ui.accent else ui.soft },
                    android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            tabBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(pill, LinearLayout.LayoutParams(ui.dp(60), ui.dp(32)))
                addView(ui.text(name, 12f, if (on) ui.ink else ui.soft).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                    setPadding(0, ui.dp(4), 0, 0)
                })
                setOnClickListener { showTab(n) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (i) { 0 -> ride.refresh(); 1 -> nav.refresh(); 2 -> trips.refresh(); 3 -> fuel.refresh(); else -> more.rebuild() }
    }

    private fun openOverlay(v: View) {
        overlay = v
        content.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        content.addView(v)
        if (v === tests.view) tests.update()
    }

    private fun closeOverlay() = showTab(tab)

    private fun openTrip(t: Trip) = openOverlay(TripDetail(this, ui, t) { closeOverlay(); trips.refresh() }.view)

    // ───────────── permissions ─────────────

    private var onLocationGranted: (() -> Unit)? = null
    private val REQ_NAV_LOCATION = 3

    /** Navigation can't work without precise location: ask every time it's missing. */
    private fun requireLocation(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { then(); return }
        onLocationGranted = then
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_NAV_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NAV_LOCATION) return
        val then = onLocationGranted
        onLocationGranted = null
        // Android won't show the prompt again after a refusal; the message offers the settings page.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) then?.invoke()
        else DashHub.showNavError(DashHub.NO_LOCATION_ACCESS)
    }

    // ───────────── Location on? ─────────────

    private var lastLocationAsk = 0L
    private val REQ_LOCATION_ON = 5

    /**
     * Location (GPS) switched off: show Android's one-tap "Turn on location?" box. Asked when the app
     * opens, at most once every 2 minutes so it never nags.
     */
    private fun ensureLocationOn() {
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        if (lm.isLocationEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastLocationAsk != 0L && now - lastLocationAsk < 120_000) return
        lastLocationAsk = now
        askLocationOn()
    }

    private var onLocationOn: (() -> Unit)? = null
    private var onLocationRefused: (() -> Unit)? = null

    /** Location switched on (asks with Android's one-tap box if it's off), then [then]; [refused] if the rider says no. */
    private fun withLocationOn(then: () -> Unit, refused: () -> Unit) {
        val lm = getSystemService(android.location.LocationManager::class.java)
        if (lm == null || lm.isLocationEnabled) { then(); return }
        onLocationOn = then
        onLocationRefused = refused
        askLocationOn()
    }

    /** Start recording: needs Location permission and Location on (the GPS measures the ride). */
    private fun startRecording() = requireLocation {
        withLocationOn(
            then = { DashService.start(this); DashHub.warmPosition(); DashHub.startRide() },
            refused = {
                val v = DashHub.vehicle
                if (v.dash && DashHub.connected) {
                    // The Suzuki still gives distance and fuel over Bluetooth; just no map.
                    DashService.start(this); DashHub.startRide()
                    android.widget.Toast.makeText(this, "Recording from the bike. No route map without Location.", android.widget.Toast.LENGTH_LONG).show()
                } else android.widget.Toast.makeText(this, "Turn on Location to record a ride", android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun askLocationOn() = LocationOn.ask(this, REQ_LOCATION_ON) { locationResult(it) }

    private fun locationResult(on: Boolean) {
        val then = onLocationOn
        val refused = onLocationRefused
        onLocationOn = null
        onLocationRefused = null
        if (on) { DashHub.warmPosition(); then?.invoke() } else refused?.invoke()
    }

    // ───────────── Bluetooth ─────────────

    private var onBluetoothOn: (() -> Unit)? = null
    private val REQ_BLUETOOTH = 4
    private val REQ_SETUP = 6
    private val REQ_BACKUP = 7
    private val REQ_RESTORE = 8

    /** Bluetooth off: ask Android to turn it on (system "Allow RogerDash to turn on Bluetooth?"), then go on. */
    private fun withBluetooth(then: () -> Unit) {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || adapter.isEnabled) { then(); return }
        onBluetoothOn = then
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BLUETOOTH) }
            .onFailure { onBluetoothOn = null; startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOCATION_ON) { locationResult(resultCode == RESULT_OK); return }
        if (requestCode == REQ_SETUP) {
            setupOpen = false
            DashHub.setupDone = true // finished, skipped or backed out: don't open it again by itself
            more.rebuild(); refreshAll(); connectKnownBike(); return
        }
        if (requestCode == REQ_BACKUP) { if (resultCode == RESULT_OK) data?.data?.let { writeBackup(it) }; return }
        if (requestCode == REQ_RESTORE) { if (resultCode == RESULT_OK) data?.data?.let { readBackup(it) }; return }
        if (requestCode != REQ_BLUETOOTH) return
        val then = onBluetoothOn
        onBluetoothOn = null
        if (resultCode == RESULT_OK) then?.invoke()
    }

    /** Connect button: Bluetooth on, background service, then (re)connect now. */
    private fun connectNow() = withBluetooth {
        DashService.start(this)
        askLocation()
        DashHub.connectBike()
    }

    /** GPS draws the route; the ride still records from the bike without it. Ask once. */
    private fun askLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("dash", MODE_PRIVATE)
        if (p.getBoolean("askedLocation", false)) return
        p.edit().putBoolean("askedLocation", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
    }
}
