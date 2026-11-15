package app.roger.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-wide state shared by the service and the screens: the dashboard link, live bike data,
 * the ride being recorded, saved trips.
 *
 * Rides start by themselves when the bike connects (ignition on) and are saved a few minutes after
 * it disconnects (ignition off), so a short stop doesn't split a ride. The last GPS position is
 * kept as "where I parked".
 */
@SuppressLint("StaticFieldLeak", "MissingPermission")
object DashHub : DashLink.Listener {
    private const val END_AFTER_MS = 3 * 60_000L

    lateinit var app: Context
        private set
    lateinit var link: DashLink
        private set
    private lateinit var realStore: TripStore
    private var demoStore: TripStore? = null

    /** Saved rides, fuel log, parked spot. In demo mode, the throwaway demo copy. */
    val store: TripStore get() = demoStore ?: realStore

    /** Demo mode: made-up data shown as if the bike were connected; never touches real rides. */
    var demo = false
        private set
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var state = "Not connected"; private set
    var connected = false; private set
    var bike: Frames.BikeData? = null; private set
    private var bikeAt = 0L
    val bikeFresh: Boolean get() = connected && SystemClock.elapsedRealtime() - bikeAt < 5000

    var ride: RideRecorder? = null; private set
    val logLines = ArrayDeque<String>()
    val found = ArrayList<Pair<BluetoothDevice, String>>()

    /** Set by [DashService] so it can switch GPS on and off with the ride. */
    var onRideChanged: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("dash", Context.MODE_PRIVATE)
        realStore = TripStore(app)
        Demo.wipe(app) // leftovers from a demo that ended with the app being killed
        link = DashLink(app, this)
        link.riderName = riderName
        Voice.init(app)
        Range.fromDash = {
            when {
                demo -> null
                dashAvg != null -> dashAvg!! to "your dash's trip average (typed in)"
                else -> null
            }
        }
        Range.current = { vehicle }
        Range.dashBike = { vehicles.firstOrNull { it.dash } ?: Vehicle.defaultFor(model) }
        PhoneAlerts.start(app)
    }

    // ───────────── setup and model ─────────────

    /** The setup wizard was finished or skipped; until then it opens with the app. */
    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(v) = prefs.edit().putBoolean("setupDone", v).apply()

    /**
     * The rider's Suzuki model (or none), chosen in setup. Installs from before models existed were all
     * on the V-Strom 250 SX, which is also the default until setup picks.
     */
    val model: DashModel
        get() = DashModel.of(prefs.getString("model", null)) ?: DashModel.VSTROM_250_SX

    /**
     * Choose the model: the main vehicle takes the model's name, dash and known figures. Figures the rider
     * typed stay unless the model knows better (setup asks for them next).
     */
    fun setModel(m: DashModel) {
        val old = model
        prefs.edit().putString("model", m.id).apply()
        val main = vehicles.firstOrNull { it.id == Vehicle.MAIN } ?: Vehicle.defaultFor(m)
        val def = Vehicle.defaultFor(m)
        saveVehicle(main.copy(
            name = if (main.name == old.vehicleName || main.name.isBlank()) def.name else main.name,
            dash = m.suzuki,
            bars = m.bars,
            tankL = m.tankL ?: main.tankL,
            typicalKmpl = m.typicalKmpl ?: main.typicalKmpl,
        ))
        if (!m.suzuki) { disconnectBike(); forgetBike() }
        onLog("Vehicle model: ${m.title}${if (m.suzuki && !m.tested) " (not tested yet)" else ""}")
    }

    // ───────────── calls and messages on the dash ─────────────

    /** Incoming and missed calls (phone and WhatsApp) on the dash. */
    var dashCalls: Boolean
        get() = prefs.getBoolean("dashCalls", true)
        set(v) = prefs.edit().putBoolean("dashCalls", v).apply()

    /** Who an SMS or WhatsApp message is from, on the dash (never the text). */
    var dashMessages: Boolean
        get() = prefs.getBoolean("dashMessages", true)
        set(v) = prefs.edit().putBoolean("dashMessages", v).apply()

    /** After a backup is restored: read rides and fill-ups from disk again. */
    fun reloadStore() {
        realStore = TripStore(app)
        link.riderName = riderName
        changed()
    }

    // ───────────── vehicles ─────────────

    /** The rider's bikes and scooters; the Suzuki first. */
    val vehicles: List<Vehicle>
        get() {
            val raw = prefs.getString("vehicles", null) ?: return listOf(Vehicle.defaultFor(model))
            return runCatching {
                val a = org.json.JSONArray(raw)
                (0 until a.length()).map { Vehicle.fromJson(a.getJSONObject(it)) }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(Vehicle.defaultFor(model))
        }

    private fun saveVehicles(l: List<Vehicle>) = prefs.edit().putString("vehicles", org.json.JSONArray(l.map { it.toJson() }).toString()).apply()

    /** "Multiple vehicles" (More): off = just the Suzuki, no vehicle switches anywhere. */
    var multiVehicle: Boolean
        get() = prefs.getBoolean("multiVehicle", false)
        set(v) { prefs.edit().putBoolean("multiVehicle", v).apply(); changed() }

    /** Vehicle switches and names are shown only with the option on and more than one vehicle added. */
    val showVehicles: Boolean get() = multiVehicle && vehicles.size > 1

    /** The vehicle the app shows and records for now. Switches to the Suzuki by itself when it connects. */
    var vehicle: Vehicle
        get() = (if (multiVehicle) prefs.getString("vehicle", null)?.let { id -> vehicles.firstOrNull { it.id == id } } else null)
            ?: vehicles.first()
        set(v) { prefs.edit().putString("vehicle", v.id).apply(); changed() }

    fun saveVehicle(v: Vehicle) {
        val l = vehicles.toMutableList()
        val i = l.indexOfFirst { it.id == v.id }
        if (i >= 0) l[i] = v else l += v
        saveVehicles(l)
        changed()
    }

    /** Removes a GPS-only vehicle from the list (its rides and fill-ups stay, under its old name). */
    fun deleteVehicle(v: Vehicle) {
        if (v.id == Vehicle.MAIN) return
        saveVehicles(vehicles.filterNot { it.id == v.id })
        if (prefs.getString("vehicle", null) == v.id) prefs.edit().remove("vehicle").apply()
        changed()
    }

    fun newVehicleId() = "v" + System.currentTimeMillis().toString(36)

    /** Name of a vehicle by id, also for removed ones. */
    fun vehicleName(id: String) = vehicles.firstOrNull { it.id == id }?.name ?: if (id == Vehicle.MAIN) model.vehicleName else "Removed vehicle"

    /** The bike doesn't send its trip average (checked 2026-11-14), so there is no bike-sent mileage. */
    val bikeEconomy: Double? get() = null

    /** Engine temperature from the bike ('7' byte 25), °C, while connected. Only where that byte is known to be it. */
    val engineTempC: Int? get() = engineTemp(bike?.takeIf { bikeFresh })

    fun engineTemp(b: Frames.BikeData?): Int? = b?.engineTempC?.takeIf { model.engineTemp }

    /** The average the rider read off the dash (AVG on trip A/B) and typed in. */
    var dashAvg: Double?
        get() = prefs.getFloat("dashAvg", -1f).takeIf { it > 0 }?.toDouble()
        set(v) = prefs.edit().putFloat("dashAvg", v?.toFloat() ?: -1f).apply()

    private var lastFuelRaw: Int? = null

    // ───────────── settings ─────────────

    var riderName: String
        get() = prefs.getString("rider", "Rider") ?: "Rider"
        set(v) { prefs.edit().putString("rider", v).apply(); link.riderName = v }

    var autoRide: Boolean
        get() = prefs.getBoolean("autoRide", true)
        set(v) = prefs.edit().putBoolean("autoRide", v).apply()

    var bikeAddress: String?
        get() = prefs.getString("bike", null)
        private set(v) = prefs.edit().putString("bike", v).apply()

    var bikeName: String?
        get() = prefs.getString("bikeName", null)
        private set(v) = prefs.edit().putString("bikeName", v).apply()

    fun forgetBike() { bikeAddress = null; bikeName = null; changed() }

    /** Bike name for the screen. */
    val shownBikeName: String? get() = if (demo) "${model.vehicleName} (demo)" else bikeName

    fun startDemo() {
        if (demo) return
        if (ride != null) stopRide() // save a real ride first
        link.disconnect()
        demoStore = Demo.build(app)
        demo = true
        connected = true
        state = "Connected"
        // A ride that has been going for 24 minutes.
        ride = RideRecorder(alreadyMs = 24 * 60_000L).apply { seed(17.6, 21 * 60, 94) }
        Demo.startLive()
        onLog("Demo mode on")
        changed()
    }

    fun stopDemo() {
        if (!demo) return
        Demo.stopLive()
        main.removeCallbacks(endRide)
        ride = null
        legOpen = false
        nav = null
        navigator = null
        lastFix = null // the demo's made-up position must not bias real searches
        bike = null
        connected = false
        state = "Not connected"
        demo = false
        demoStore = null
        Demo.wipe(app)
        onLog("Demo mode off")
        changed()
    }

    // ───────────── listeners ─────────────

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun changed() = main.post { listeners.forEach { it() } }

    // ───────────── connecting ─────────────

    private var autoConnectNext = false

    /** Connect to the saved bike, or scan and connect to the first Suzuki dashboard found. */
    fun connectBike() {
        if (!model.suzuki) return
        val saved = bikeAddress?.let { link.deviceFor(it) }
        if (saved != null) link.connect(saved, auto = true)
        else { autoConnectNext = true; found.clear(); link.scan() }
    }

    fun disconnectBike() {
        autoConnectNext = false
        link.disconnect()
        onState("Not connected", false)
    }

    /** Every log line also goes to files/dash.log (kept ~1 MB), so a ride can be checked afterwards. */
    private val logFile by lazy { java.io.File(app.filesDir, "dash.log") }
    private val logTime = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    private val logIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onLog(line: String) {
        val stamped = "${logTime.format(java.util.Date())} $line\n"
        logIo.execute {
            runCatching {
                if (logFile.length() > 1_000_000) logFile.renameTo(java.io.File(app.filesDir, "dash.old.log"))
                logFile.appendText(stamped)
            }
        }
        logLines.addFirst(line)
        while (logLines.size > 150) logLines.removeLast()
        changed()
    }

    override fun onState(text: String, connected: Boolean) {
        val was = this.connected
        this.connected = connected
        state = text
        if (connected && !was) {
            link.device?.let { bikeAddress = it.address; bikeName = it.name ?: bikeName }
            main.removeCallbacks(endRide)
            rangeBarsSent = -1; connectedAt = SystemClock.elapsedRealtime() // range popup again on this connection
            // The Suzuki is on: it's the vehicle being ridden (unless a scooter ride is being recorded right now).
            if (ride == null && !vehicle.dash) vehicles.firstOrNull { it.dash }?.let {
                prefs.edit().putString("vehicle", it.id).apply(); logBoth("Switched to ${it.name} (it connected)")
            }
            if (ride == null && autoRide) startRide()
            sendNav() // already navigating when the bike connected
        }
        if (!connected && was && ride != null && autoRide && ride?.vehicle == Range.dashBike().id) {
            // Ignition off (or out of range): end the ride if the bike doesn't come back soon.
            main.removeCallbacks(endRide)
            main.postDelayed(endRide, END_AFTER_MS)
        }
        changed()
    }

    override fun onFound(device: BluetoothDevice, name: String) {
        if (found.none { it.first.address == device.address }) found += device to name
        if (autoConnectNext) { autoConnectNext = false; link.connect(device) }
        changed()
    }

    /** The bike's last fuel bars and odometer, kept for when it's parked. */
    val lastFuelBars: Int? get() = prefs.getInt("lastFuel", -1).takeIf { it >= 0 }
    val lastOdo: Int? get() = prefs.getInt("lastOdo", -1).takeIf { it >= 0 }
    val lastBikeTime: Long get() = prefs.getLong("lastBikeTime", 0)

    /** Everything the bike last told us (odometer, trips, fuel), for when it isn't connected. */
    val lastBike: Frames.BikeData?
        get() {
            if (lastBikeTime == 0L) return null
            fun f(k: String) = prefs.getFloat(k, -1f).takeIf { it >= 0 }?.toDouble()
            return Frames.BikeData(null, lastOdo, f("lastTripA"), f("lastTripB"), lastFuelBars)
        }

    /** Google Places API key (optional; much better place search than OpenStreetMap). Never leaves the phone except to Google. */
    var googleKey: String
        get() = prefs.getString("googleKey", "") ?: ""
        set(v) = prefs.edit().putString("googleKey", v.trim()).apply()

    /** Google's map (when the app has one built in) instead of the OpenStreetMap one. On unless switched off in More. */
    var googleMap: Boolean
        get() = prefs.getBoolean("googleMap", true)
        set(v) = prefs.edit().putBoolean("googleMap", v).apply()

    override fun onBike(data: Frames.BikeData) {
        bike = data
        bikeAt = SystemClock.elapsedRealtime()
        if (!demo) prefs.edit().apply {
            data.fuelBars?.let { putInt("lastFuel", it) }
            data.odoKm?.let { putInt("lastOdo", it) }
            data.tripAKm?.let { putFloat("lastTripA", it.toFloat()) }
            data.tripBKm?.let { putFloat("lastTripB", it.toFloat()) }
            putLong("lastBikeTime", System.currentTimeMillis())
        }.apply()
        if (data.fuelRaw != lastFuelRaw) {
            lastFuelRaw = data.fuelRaw
            // Raw value logged on every model: on untested ones it shows what the byte really carries.
            logBoth("Bike byte 25-27: raw ${data.fuelRaw?.let { "0x%06X".format(it) } ?: "none"} (${engineTemp(data)?.let { "engine $it °C" } ?: "meaning not known for ${model.title}"}, odo ${data.odoKm})")
        }
        ride?.takeIf { it.vehicle == Range.dashBike().id }?.onBike(data) // not into a scooter ride
        rangeToDash(data.fuelBars)
        lowFuelCheck(data.fuelBars)
        speedPopup(data.speedKmh)
        changed()
    }

    // ───────────── range on the dash ─────────────
    // The dash has no range field, so range goes up as a short message popup ('5' frame, sender text):
    // once after connecting, whenever a fuel bar goes out, and when a route is longer than the range.

    var rangeOnDash: Boolean
        get() = prefs.getBoolean("rangeOnDash", true)
        set(v) = prefs.edit().putBoolean("rangeOnDash", v).apply()

    private var rangeBarsSent = -1
    private var connectedAt = 0L
    private val clearRange = Runnable { if (connected && !demo) repeat(3) { link.send(Frames.message(lastRangeText, 0, isNew = false)) } }
    private var lastRangeText = ""

    /** Show [text] on the dash for ~10 s. */
    fun popOnDash(text: String) {
        if (!connected || demo) return
        lastRangeText = text
        main.removeCallbacks(clearRange)
        repeat(3) { link.send(Frames.message(text, 1)) }
        main.postDelayed(clearRange, 10_000)
        logBoth("Dash: $text")
    }

    /** In-app log and logcat (tag "Dash"), for lines worth reading after a ride. */
    private fun logBoth(s: String) { android.util.Log.i("Dash", s); onLog(s) }

    private fun rangeToDash(bars: Int?) {
        if (!rangeOnDash || bars == null || demo) return
        if (SystemClock.elapsedRealtime() - connectedAt < 6000) return // let the dash finish its welcome screen
        if (rangeBarsSent >= 0 && bars >= rangeBarsSent) { if (bars > rangeBarsSent) rangeBarsSent = bars; return } // refuelled: quietly track
        val est = Range.estimate(bars, store) ?: return
        rangeBarsSent = bars
        popOnDash(Range.dashText(est.km))
    }

    // ───────────── low fuel reminder (phone) ─────────────

    var lowFuelReminder: Boolean
        get() = prefs.getBoolean("lowFuelReminder", true)
        set(v) = prefs.edit().putBoolean("lowFuelReminder", v).apply()

    private var lowFuelRemindedBars = -1

    /** Range under [Range.LOW_KM]: a phone notification (and a spoken line), once per fuel bar going out. */
    private fun lowFuelCheck(bars: Int?) {
        if (!lowFuelReminder || bars == null || demo) return
        if (lowFuelRemindedBars in 0 until bars) lowFuelRemindedBars = -1 // refuelled
        if (bars == lowFuelRemindedBars) return
        val est = Range.estimate(bars, store) ?: return
        if (est.km >= Range.LOW_KM) return
        lowFuelRemindedBars = bars
        val text = "About ${est.km} km left on $bars of ${Range.dashBike().bars.takeIf { it > 0 } ?: Range.BARS} bars (estimate)."
        logBoth("Low fuel reminder: $text")
        DashService.alert(app, "Fuel low", text + if (navigator != null) " Tap ⛽ on the map for pumps on your route." else "")
        if (voiceOn) Voice.say("Fuel is low. About ${est.km} kilometres left.")
    }

    // ───────────── more dash popups (each can be switched off in More) ─────────────

    /** Street name of the next turn, ~300 m before it: "MG RD". */
    var dashStreets: Boolean
        get() = prefs.getBoolean("dashStreets", true)
        set(v) = prefs.edit().putBoolean("dashStreets", v).apply()

    /** "10 MIN LEFT" once per trip. */
    var dashTimeLeft: Boolean
        get() = prefs.getBoolean("dashTimeLeft", true)
        set(v) = prefs.edit().putBoolean("dashTimeLeft", v).apply()

    /** "SPEED 92 KMH" when going over [speedLimit]. Off until the rider picks a limit. */
    var dashSpeed: Boolean
        get() = prefs.getBoolean("dashSpeed", false)
        set(v) = prefs.edit().putBoolean("dashSpeed", v).apply()

    var speedLimit: Int
        get() = prefs.getInt("speedLimit", 80)
        set(v) = prefs.edit().putInt("speedLimit", v.coerceIn(20, 200)).apply()

    private var streetShownFor = -1
    private var timeLeftShown = false
    private var overLimitCount = 0
    private var lastSpeedWarn = 0L

    private const val STREET_BEFORE_M = 300

    /** Called with each navigation step: street name before the turn, and the 10-minute reminder. */
    private fun navPopups(s: NavStep) {
        if (s.status != '1' || navigator == null) return
        val toTurn = s.toTurnM ?: return
        if (dashStreets && s.street != null && s.turnId != streetShownFor && toTurn in 20..STREET_BEFORE_M &&
            s.icon != Frames.Icon.STRAIGHT && s.icon != Frames.Icon.ARRIVE) {
            streetShownFor = s.turnId
            popOnDash(Dash12.street(s.street, model.textMax))
        }
        val leftSec = s.eta?.let { (it.timeInMillis - System.currentTimeMillis()) / 1000 } ?: return
        if (dashTimeLeft && !timeLeftShown && leftSec in 120..600) {
            timeLeftShown = true
            popOnDash("10 MIN LEFT")
        }
    }

    /** Speed from the bike (or GPS when the bike isn't connected), km/h. Warns once a minute at most. */
    private fun speedPopup(kmh: Int?) {
        if (!dashSpeed || kmh == null) return
        if (kmh > speedLimit) overLimitCount++ else overLimitCount = 0
        val now = SystemClock.elapsedRealtime()
        if (overLimitCount >= 2 && now - lastSpeedWarn > 60_000) { // 2 readings in a row: not a blip
            lastSpeedWarn = now
            popOnDash("SPEED $kmh KMH")
        }
    }

    /** Navigation start: warn if the destination is beyond the range. */
    private fun checkRangeForRoute(routeM: Double) {
        if (!rangeOnDash) return
        val bars = bike?.fuelBars ?: lastFuelBars ?: return
        val est = Range.estimate(bars, store) ?: return
        if (routeM / 1000 > est.km * 0.9) popOnDash(Range.refuelText(est.km))
    }

    fun onLocation(l: Location) {
        lastFix = LatLon(l.latitude, l.longitude)
        lastFixAt = SystemClock.elapsedRealtime()
        ride?.onLocation(l)
        lastBearing = if (l.hasBearing() && l.hasSpeed() && l.speed >= 3f) l.bearing else null
        lastSpeed = if (l.hasSpeed()) l.speed else null
        if (!bikeFresh) speedPopup(lastSpeed?.let { (it * 3.6f).toInt() }) // the bike's own speed is preferred
        navStops.firstOrNull()?.pos?.let { s ->
            if (navigator != null && Geo.dist(s, LatLon(l.latitude, l.longitude)) < 60) {
                val done = navStops.removeAt(0)
                logBoth("Stop reached: ${done.name}")
            }
        }
        navigator?.let { n ->
            val wasOff = n.offRoute
            onNav(n.onLocation(LatLon(l.latitude, l.longitude), if (l.hasAccuracy()) l.accuracy else 10f, lastBearing, lastSpeed))
            if (n.offRoute != wasOff) logBoth(if (n.offRoute) "Off route at %.5f,%.5f (heading ${lastBearing?.toInt()}, %.0f m accuracy)".format(l.latitude, l.longitude, l.accuracy) else "Back on route")
            if (n.arrived) {
                endLeg("arrived") // the sub-trip ends on arrival, not when guidance closes 30 s later
                main.removeCallbacks(endNav); main.postDelayed(endNav, 30_000)
            }
        }
        changed()
    }

    // ───────────── navigation ─────────────

    /** The step shown now (ours, or Google Maps' if that is navigating instead), or null. */
    var nav: NavStep? = null
        private set
    var lastMapsRaw = ""

    var lastFix: LatLon? = null; private set
    private var lastFixAt = 0L
    /** GPS heading while moving (null when stopped or unknown), and speed in m/s. */
    var lastBearing: Float? = null; private set
    var lastSpeed: Float? = null; private set

    /** Turn-by-turn guidance in progress. */
    var navigator: Navigator? = null; private set
    /** A chosen destination whose route is being worked out. */
    var planning: Place? = null; private set
    /** Route worked out, waiting for "Start". */
    var preview: Pair<Place, Route>? = null; private set
    var navError: String? = null; private set

    /** GPS is needed for a ride or for guidance. */
    val gpsWanted: Boolean get() = ride != null || navigator != null || planning != null

    private val endNav = Runnable { stopNavigation() }

    /** Pick a destination: get our position, work out the route, show it for "Start". */
    private var planToken = 0

    // ───────────── navigation settings, stops, Home/Work ─────────────

    var voiceOn: Boolean
        get() = prefs.getBoolean("voiceOn", true)
        set(v) = prefs.edit().putBoolean("voiceOn", v).apply()
    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoidTolls", false)
        set(v) = prefs.edit().putBoolean("avoidTolls", v).apply()
    var avoidHighways: Boolean
        get() = prefs.getBoolean("avoidHighways", false)
        set(v) = prefs.edit().putBoolean("avoidHighways", v).apply()
    var avoidUnpaved: Boolean
        get() = prefs.getBoolean("avoidUnpaved", false)
        set(v) = prefs.edit().putBoolean("avoidUnpaved", v).apply()
    var routeStyle: RouteStyle
        get() = runCatching { RouteStyle.valueOf(prefs.getString("routeStyle", null) ?: "FASTEST") }.getOrDefault(RouteStyle.FASTEST)
        set(v) = prefs.edit().putString("routeStyle", v.name).apply()
    val routeOptions get() = RouteOptions(avoidTolls, avoidHighways, avoidUnpaved, routeStyle)

    /** The route being previewed is a weekend loop (round trip). */
    var planIsLoop = false; private set

    /**
     * Weekend loop: a round trip of about [km] road km from here, on scenic small roads (no highways, no tolls).
     * Two turning points make a triangle out and back; a few directions are tried and the one closest to [km] is kept.
     */
    fun planLoop(km: Int) {
        val token = ++planToken
        planStops.clear()
        planIsLoop = true
        planning = Place("Weekend loop · about $km km", "", null); preview = null; navError = null; planDest = null
        onRideChanged?.invoke(); changed()
        currentPosition { from ->
            if (token != planToken) return@currentPosition
            if (from == null) {
                planning = null; navError = if (!locationOn()) "Turn on Location to plan a loop." else "Can't find your position yet. Try again in a moment."
                onRideChanged?.invoke(); changed(); return@currentPosition
            }
            val opts = RouteOptions(avoidTolls = true, avoidHighways = true, avoidUnpaved = avoidUnpaved, style = RouteStyle.SCENIC)
            Thread {
                val side = km * 1000.0 / 1.35 / 3 // straight-line triangle side; roads wind ~1.35× the straight line
                val first = kotlin.random.Random.nextInt(360)
                var best: Route? = null
                for (k in 0 until 3) {
                    if (token != planToken) return@Thread
                    val b = (first + k * 120) % 360.0
                    val pts = listOf(from, Geo.move(from, b, side), Geo.move(from, b + 60, side), from)
                    val r = runCatching { Router.route(pts, options = opts) }.getOrNull() ?: continue
                    if (best == null || Math.abs(r.lengthM / 1000 / km - 1) < Math.abs(best.lengthM / 1000 / km - 1)) best = r
                    if (r.lengthM / 1000 / km in 0.8..1.25) break
                }
                main.post {
                    if (token != planToken) return@post
                    planning = null
                    val r = best
                    if (r == null) navError = "Couldn't make a loop from here. Check the internet connection."
                    else {
                        // Turning points where the route really passes (a point out at sea snaps to the coast road).
                        r.maneuvers.filter { it.isStop }.forEachIndexed { i, m -> planStops += Place("Loop point ${i + 1}", "", r.shape[m.beginIndex]) }
                        val home = Place("Weekend loop · ${Fmt.km(r.lengthM / 1000)}", "Back to the start", from)
                        planDest = home
                        preview = home to r
                        logBoth("Loop planned: ${Fmt.km(r.lengthM / 1000)} (asked $km km, route: ${Router.lastSource})")
                    }
                    onRideChanged?.invoke(); changed()
                }
            }.start()
        }
    }

    /** Saved "Home" / "Work" places ([key] = "home" or "work"). */
    fun savedPlace(key: String): Place? = prefs.getString("place_$key", null)?.split('\t')?.takeIf { it.size == 4 }
        ?.let { Place(it[0], it[1], LatLon(it[2].toDouble(), it[3].toDouble())) }

    fun setSavedPlace(key: String, p: Place?) {
        val pos = p?.pos
        prefs.edit().apply {
            if (p == null || pos == null) remove("place_$key") else putString("place_$key", "${p.name}\t${p.detail}\t${pos.lat}\t${pos.lon}")
        }.apply()
        changed()
    }

    /** Stops on the way to the destination being planned (in order). */
    val planStops = ArrayList<Place>()
    /** The destination being planned (kept while stops are added). */
    var planDest: Place? = null; private set
    /** Stops still ahead while navigating. */
    val navStops = ArrayList<Place>()

    /** Add a stop to the route being planned: placed in route order, then the route is worked out again. */
    fun addStop(p: Place) {
        val dest = planDest ?: return
        Places.resolve(p) { r ->
            main.post {
                val pos = r?.pos ?: return@post
                val stop = r.copy(googleId = null)
                // Order by how far along the current preview route the stop lies.
                val shape = preview?.second?.shape
                fun along(x: LatLon) = shape?.withIndex()?.minByOrNull { Geo.dist(it.value, x) }?.index ?: 0
                planStops += stop
                planStops.sortBy { along(it.pos!!) }
                logBoth("Stop added: ${stop.name}")
                plan(dest, keepStops = true)
            }
        }
    }

    fun removeStop(p: Place) {
        planStops.remove(p)
        planDest?.let { plan(it, keepStops = true) }
    }

    /** While navigating: go via [p] first (e.g. a petrol pump), then on to the destination. */
    fun addStopNow(p: Place) {
        val n = navigator ?: return
        val from = lastFix ?: return
        navStops.add(0, p)
        logBoth("Going via ${p.name}")
        n.forceReroute()
        reroute(from)
    }

    /** Petrol pumps along the route being previewed, or the rest of the route being navigated. */
    fun findFuel(done: (List<FuelStop>?) -> Unit) {
        val n = navigator
        val shape = n?.route?.shape ?: preview?.second?.shape ?: return done(null)
        val from = n?.alongM ?: 0.0
        Thread {
            val r = runCatching { FuelStops.along(shape, from) }
            r.exceptionOrNull()?.let { logBoth("Fuel search failed: ${it.message}") }
            r.getOrNull()?.let { logBoth("Fuel search: ${it.size} found") }
            main.post { done(r.getOrNull()) }
        }.start()
    }

    fun plan(picked: Place) = plan(picked, keepStops = false)

    private fun plan(picked: Place, keepStops: Boolean) {
        if (!keepStops) { planStops.clear(); planIsLoop = false }
        planDest = picked
        val token = ++planToken
        planning = picked; preview = null; navError = null
        onRideChanged?.invoke() // GPS on while we find our position
        changed()
        Places.resolve(picked) { resolved ->
            main.post {
                if (token != planToken) return@post
                if (resolved?.pos == null) {
                    planning = null; navError = "Couldn't get that place's location. Check the internet connection."
                    onRideChanged?.invoke(); changed()
                } else planRoute(resolved, token)
            }
        }
    }

    private fun planRoute(place: Place, token: Int) {
        val to = place.pos ?: return
        currentPosition { from ->
            if (token != planToken) return@currentPosition
            if (from == null) {
                planning = null
                navError = when {
                    !locationOn() -> "Turn on Location to navigate."
                    app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED -> NO_LOCATION_ACCESS
                    else -> "Can't find your position yet. Try again in a moment, ideally outdoors."
                }
                onRideChanged?.invoke(); changed()
                return@currentPosition
            }
            val points = listOf(from) + planStops.mapNotNull { it.pos } + to
            val opts = routeOptions
            Thread {
                val r = runCatching { Router.route(points, options = opts) }
                main.post {
                    if (token != planToken) return@post // cancelled meanwhile
                    planning = null
                    r.onSuccess { preview = place to it }.onFailure { navError = "No route found. Check the internet connection." }
                    onRideChanged?.invoke(); changed()
                }
            }.start()
        }
    }

    fun cancelPlan() {
        planToken++; planning = null; preview = null; navError = null; planDest = null; planStops.clear(); planIsLoop = false
        onRideChanged?.invoke(); changed()
    }

    /** Re-plan the current destination (e.g. after changing what to avoid). */
    fun replan() { planDest?.let { plan(it, keepStops = true) } }

    /** Location access missing: shown in the navigation section with a way to fix it. */
    const val NO_LOCATION_ACCESS = "RogerDash needs location access to navigate. Allow \"Precise\" location."

    fun showNavError(msg: String) { navError = msg; changed() }

    fun startNavigation() {
        val (place, route) = preview ?: return
        preview = null
        if (!planIsLoop) remember(place) // a loop isn't a place to go back to
        planIsLoop = false
        navStops.clear(); navStops.addAll(planStops)
        planStops.clear(); planDest = null
        val n = Navigator(place, route) { pos -> reroute(pos) }
        navigator = n
        // Each started navigation is a sub-trip of the ride (start recording one if none is running).
        if (ride == null && !demo) startRide()
        ride?.startLeg(place.name)
        legOpen = true
        Voice.reset()
        lastNavStatus = '1'
        streetShownFor = -1
        timeLeftShown = route.timeSec < 15 * 60 // short trips don't need the reminder
        main.removeCallbacks(endNav)
        logBoth("Navigating to ${place.name}: ${Fmt.km(route.lengthM / 1000)} (route: ${Router.lastSource})")
        onNav(n.initial())
        checkRangeForRoute(route.lengthM.toDouble())
        onRideChanged?.invoke()
    }

    /** Demo mode: real guidance along a made-up route (nothing is sent to a bike). */
    fun demoNavigate(place: Place, route: Route) {
        if (!demo) return
        navigator = Navigator(place, route) {}
        onNav(navigator!!.initial())
    }

    /** A sub-trip is open in the ride (navigation started and not yet arrived / cancelled). */
    private var legOpen = false

    private fun endLeg(reason: String) {
        if (!legOpen) return
        legOpen = false
        ride?.endLeg(reason)
        logBoth("Sub-trip ended: $reason")
    }

    fun stopNavigation() {
        main.removeCallbacks(endNav)
        // Cancelled before arriving: the sub-trip ends here, where the rider stopped navigating.
        endLeg(if (navigator?.arrived == true) "arrived" else "cancelled")
        if (navigator == null && nav == null) return
        navigator = null
        navStops.clear()
        onNavEnded()
        onRideChanged?.invoke()
    }

    private fun reroute(pos: LatLon) {
        val n = navigator ?: return
        val heading = lastBearing
        logBoth("Off route: finding a new one (heading ${heading?.toInt() ?: "unknown"})")
        if (voiceOn) Voice.say("Finding a new route")
        val opts = routeOptions
        val via = navStops.mapNotNull { it.pos }
        Thread {
            val to = n.dest.pos ?: return@Thread
            val r = runCatching { Router.route(listOf(pos) + via + to, heading, opts) }
            main.post {
                if (navigator !== n) return@post
                r.onSuccess {
                    n.setRoute(it)
                    logBoth("New route (${Router.lastSource}): ${Fmt.km(it.lengthM / 1000)}, first: ${it.maneuvers.firstOrNull()?.instruction}")
                    // We kept moving while it was worked out: place us using the latest fix.
                    onNav(n.onLocation(lastFix ?: pos, 10f, lastBearing, lastSpeed))
                }.onFailure { logBoth("Re-route failed: ${it.message}"); n.rerouteFailed() }
            }
        }.start()
    }

    private fun locationOn() = app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true

    /**
     * Get a rough position ready (for "near me" search) without waiting: Android's last known
     * position straight away, then a fresh one.
     */
    @SuppressLint("MissingPermission")
    fun warmPosition() {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val lm = app.getSystemService(android.location.LocationManager::class.java) ?: return
        if (lastFix == null) {
            listOf(android.location.LocationManager.FUSED_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { lastFix = LatLon(it.latitude, it.longitude); lastFixAt = SystemClock.elapsedRealtime() - 60_000; changed() }
        }
        if (!locationOn()) return
        runCatching {
            lm.getCurrentLocation(android.location.LocationManager.FUSED_PROVIDER, null, app.mainExecutor) { l ->
                if (l != null) { lastFix = LatLon(l.latitude, l.longitude); lastFixAt = SystemClock.elapsedRealtime(); changed() }
            }
        }
    }

    /** Our position: a recent GPS fix, else ask Android for one (up to ~15 s). */
    private fun currentPosition(done: (LatLon?) -> Unit) {
        lastFix?.let { if (SystemClock.elapsedRealtime() - lastFixAt < 120_000) { done(it); return } }
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        if (lm == null || !locationOn() ||
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { done(null); return }
        var answered = false
        val give = { p: LatLon? -> if (!answered) { answered = true; done(p) } }
        runCatching {
            lm.getCurrentLocation(android.location.LocationManager.FUSED_PROVIDER, null, app.mainExecutor) { l ->
                give(l?.let { LatLon(it.latitude, it.longitude) }
                    ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)?.let { LatLon(it.latitude, it.longitude) })
            }
        }.onFailure { give(lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)?.let { LatLon(it.latitude, it.longitude) }) }
        main.postDelayed({ give(lastFix) }, 15_000)
    }

    /** Recent destinations, newest first. */
    val recentPlaces: List<Place>
        get() = prefs.getString("places2", "")!!.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 4) Place(p[0], p[1], LatLon(p[2].toDouble(), p[3].toDouble())) else null
        }

    private fun savePlaces(list: List<Place>) = prefs.edit().putString("places2",
        list.filter { it.pos != null }.joinToString("\n") { "${it.name}\t${it.detail}\t${it.pos!!.lat}\t${it.pos.lon}" }).apply()

    private fun remember(p: Place) =
        savePlaces((listOf(p.copy(googleId = null, distM = null)) + recentPlaces.filterNot { it.name == p.name && it.detail == p.detail }).take(6))

    fun forgetPlace(p: Place) {
        savePlaces(recentPlaces.filterNot { it.name == p.name && it.detail == p.detail })
        changed()
    }

    private var lastNavStatus = '1'

    fun onNav(step: NavStep) {
        nav = step
        sendNav()
        navPopups(step)
        if (voiceOn && navigator != null) {
            Voice.onStep(step, lastSpeed)
            if (step.status == '5' && lastNavStatus != '5') Voice.say("You have arrived at ${navigator?.dest?.name ?: "your destination"}")
        }
        lastNavStatus = step.status
        changed()
    }

    fun onNavEnded() {
        if (nav == null) return
        nav = null
        link.nav = null
        if (connected && !demo) repeat(10) { link.send(Frames.navEnd()) }
        onLog("Navigation ended")
        changed()
    }

    /** Put the current step on the dash (also called when the bike (re)connects mid-route). */
    private fun sendNav() {
        val s = nav ?: return
        if (demo) return
        link.nav = Frames.nav(
            icon = s.icon, toTurnM = s.toTurnM ?: 0, remainingM = s.remainingM ?: 0,
            eta = s.eta ?: java.util.Calendar.getInstance(), status = s.status,
        )
    }

    // ───────────── rides ─────────────

    private val endRide = Runnable { stopRide() }

    fun startRide() {
        if (ride != null) return
        val v = vehicle
        ride = RideRecorder(vehicle = v.id)
        if (v.dash) bike?.let { if (bikeFresh) ride?.onBike(it) }
        onLog("Ride started (${v.name})")
        onRideChanged?.invoke()
        changed()
    }

    fun stopRide() {
        main.removeCallbacks(endRide)
        val r = ride ?: return
        ride = null
        legOpen = false // finish() closes any open sub-trip as "ride ended"
        r.lastPosition?.let { store.setParked(TripStore.Parked(it.latitude, it.longitude, System.currentTimeMillis()), r.vehicle) }
        val t = r.finish()
        if (t != null) { store.save(t); onLog("Ride saved: ${Fmt.km(t.distanceKm)} (${vehicleName(t.vehicle)})") } else onLog("Ride too short, not saved")
        // GPS-only vehicle: no fuel gauge, so check the range from the km since the last full tank.
        vehicles.firstOrNull { it.id == r.vehicle && !it.dash }?.let { v ->
            val est = Range.fromLog(store, v) ?: return@let
            if (lowFuelReminder && !demo && est.km < Range.LOW_KM)
                DashService.alert(app, "Fuel low · ${v.name}", "About ${est.km} km left since your last full tank (estimate).")
        }
        onRideChanged?.invoke()
        changed()
    }
}
