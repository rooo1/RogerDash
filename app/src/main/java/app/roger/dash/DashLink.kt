package app.roger.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bluetooth Low Energy link to the Suzuki TFT dashboard, done the way the official app does it:
 * connect, MTU 250, use the 4th GATT service (characteristic 0 = write, 1 = notify), send the
 * rider frame once, then a status frame about every second as keep-alive.
 *
 * Only one phone app can be connected to the dashboard at a time, so Suzuki Ride Connect must be
 * closed (force-stopped) while this is used.
 */
@SuppressLint("MissingPermission")
class DashLink(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(line: String)
        fun onState(text: String, connected: Boolean)
        fun onFound(device: BluetoothDevice, name: String)
        fun onBike(data: Frames.BikeData)
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BT_OFF = "Bluetooth is off"
    }

    /** Bluetooth switched on: pick up where we left off. Switched off: say so. */
    private val btState = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> if (wanted && !ready) { log("Bluetooth on: connecting"); main.postDelayed(retry, 1000) }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> { log("Bluetooth off"); if (!ready) state(BT_OFF, false) }
            }
        }
    }

    init {
        ctx.registerReceiver(btState, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writeDone = Semaphore(0)
    @Volatile private var ready = false
    @Volatile private var running = false
    private var writer: Thread? = null
    private var ticker: Thread? = null

    /** Navigation frame to repeat while navigating (the official app re-sends every ~200 ms). */
    @Volatile var nav: ByteArray? = null
    var riderName = "Rider"

    /** Keep-alive frames still to carry the "messages read" / "missed calls cleared" flag (Suzuki's app: ~3). */
    @Volatile var messagesReadFrames = 0
    @Volatile var missedClearedFrames = 0

    val connected: Boolean get() = ready

    private fun log(s: String) {
        Log.i("Dash", s)
        main.post { listener.onLog(s) }
    }

    private fun state(s: String, c: Boolean) = main.post { listener.onState(s, c) }

    // ───────────── scanning ─────────────

    private var scanning = false
    private val seen = HashSet<String>()
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: return
            if (!seen.add(r.device.address)) return
            // The official app keeps 12-character names containing SC/SB/SA, for all its dashes; V-Strom 250 SX = "SBM01…".
            val suzuki = name.length == 12 && (name.contains("SC") || name.contains("SB") || name.contains("SA"))
            log("Found ${if (suzuki) "Suzuki dashboard" else "device"}: $name (${r.rssi} dBm)")
            if (suzuki && DashModel.legacyDash(name)) log("$name looks like an older Access/Burgman dash, which uses a different checksum. RogerDash doesn't support it yet.")
            if (suzuki) main.post { listener.onFound(r.device, name) }
        }
        override fun onScanFailed(errorCode: Int) = log("Scan failed: $errorCode")
    }

    fun scan(seconds: Int = 12) {
        val s = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (s == null) { state(BT_OFF, false); return }
        if (scanning) return
        scanning = true
        seen.clear() // report the bike again on every scan (it was only ever reported once before)
        state("Looking for the bike… (ignition on)", false)
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ stopScan() }, seconds * 1000L)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        if (!ready) state("Not connected", false)
    }

    // ───────────── connection ─────────────

    /** The dashboard we're connected or connecting to. */
    @Volatile var device: BluetoothDevice? = null
        private set

    /**
     * Connect and stay connected: a direct connection attempt (fast), and if it fails or the link drops,
     * try again every few seconds until [disconnect] is called. [auto] is kept for callers; retries make it unneeded.
     */
    fun connect(device: BluetoothDevice, @Suppress("UNUSED_PARAMETER") auto: Boolean = false) {
        stopScan()
        disconnect()
        this.device = device
        wanted = true
        attempt()
    }

    /** True while we should keep (re)connecting to [device]. */
    @Volatile private var wanted = false
    private var attempts = 0
    private val retry = Runnable { if (wanted && !ready) attempt() }

    private fun attempt() {
        val d = device ?: return
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        if (adapter?.isEnabled != true) {
            // Nothing to try until Bluetooth comes back; [btState] resumes then.
            main.removeCallbacks(retry)
            state(BT_OFF, false)
            return
        }
        attempts++
        val other = otherAppHasBike(d)
        state(when {
            other -> "Another app (Suzuki Ride Connect?) is connected to the bike"
            attempts <= 1 -> "Connecting to ${d.name ?: d.address}…"
            else -> "Waiting for the bike… (ignition on)"
        }, false)
        log("Connecting to ${d.address} (try $attempts)${if (other) "; another app already holds this bike" else ""}")
        gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        // A direct attempt gives up by itself after ~30 s (status 133/147); also guard against it hanging.
        main.removeCallbacks(retry)
        main.postDelayed(retry, 35_000)
    }

    private fun scheduleRetry(delayMs: Long) {
        if (!wanted) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, delayMs)
    }

    /** The phone already has a Bluetooth link to [d] that isn't ours: some other app (Ride Connect) has it. */
    fun otherAppHasBike(d: BluetoothDevice? = device): Boolean {
        if (d == null || ready) return false
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return false
        return runCatching { bm.getConnectedDevices(BluetoothProfile.GATT).any { it.address == d.address } }.getOrDefault(false) && gatt == null
    }

    fun deviceFor(address: String): BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    fun disconnect() {
        wanted = false
        attempts = 0
        main.removeCallbacks(retry)
        running = false
        ready = false
        nav = null
        writer?.interrupt(); ticker?.interrupt()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
        queue.clear()
    }

    fun send(frame: ByteArray) {
        if (!ready) return log("Not connected; frame not sent")
        queue.offer(frame)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected (status $status); asking for MTU 250")
                g.requestMtu(250)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                ready = false
                running = false
                writer?.interrupt(); ticker?.interrupt()
                if (gatt === g) { runCatching { g.close() }; gatt = null }
                if (wanted) {
                    state(if (otherAppHasBike()) "Another app (Suzuki Ride Connect?) is connected to the bike" else "Waiting for the bike… (ignition on)", false)
                    scheduleRetry(5_000)
                } else state("Disconnected", false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu (status $status); discovering services")
            main.postDelayed({ g.discoverServices() }, 300)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("Services (status $status):")
            g.services.forEachIndexed { i, s ->
                log("  [$i] ${s.uuid}")
                s.characteristics.forEachIndexed { j, c -> log("      ($j) ${c.uuid} props=0x%02X".format(c.properties)) }
            }
            val svc = g.services.getOrNull(3)
            val w = svc?.characteristics?.getOrNull(0)
            val n = svc?.characteristics?.getOrNull(1)
            if (w == null || n == null) {
                log("Unexpected layout: no 4th service with 2 characteristics. Not sending anything.")
                state("Connected, but this doesn't look like a Suzuki dashboard", false)
                return
            }
            writeChar = w
            log("Using service ${svc.uuid}: write ${w.uuid}, notify ${n.uuid}")
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(CCCD)
            if (d == null) { log("No notification descriptor; continuing"); start(); return }
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("Notifications on (status $status)")
            start()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Write failed: $status")
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val type = if (value.size > 1) value[1].toInt().toChar() else '?'
            if (type != '7') log("Dashboard sent '$type': ${Frames.hex(value)}")
            Frames.parseBike(value)?.let { main.post { listener.onBike(it) } }
        }
    }

    /** Handshake done: rider frame once, then keep-alive status every second (+ nav if active). */
    private fun start() {
        main.removeCallbacks(retry)
        attempts = 0
        ready = true
        running = true
        state("Connected to the dashboard", true)
        writer = thread(name = "dash-writer") { writeLoop() }
        queue.offer(Frames.rider(riderName, reconnect = true))
        ticker = thread(name = "dash-ticker") {
            var t = 0
            try {
                while (running) {
                    Thread.sleep(250)
                    nav?.let { queue.offer(it) }
                    if (++t % 4 == 0) queue.offer(statusFrame())
                }
            } catch (_: InterruptedException) {}
        }
    }

    private fun writeLoop() {
        try {
            while (running) {
                val f = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val g = gatt ?: break
                val c = writeChar ?: break
                writeDone.drainPermits()
                val r = g.writeCharacteristic(c, f, c.writeType)
                if (r != BluetoothGatt.GATT_SUCCESS) { log("Write not queued ($r)"); Thread.sleep(100); continue }
                if (f[1] != '3'.code.toByte() && f[1] != '1'.code.toByte()) log("Sent '${f[1].toInt().toChar()}': ${Frames.hex(f)}")
                if (!writeDone.tryAcquire(2, TimeUnit.SECONDS)) log("No write confirmation")
            }
        } catch (_: InterruptedException) {}
    }

    private fun statusFrame(): ByteArray {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pct = level * 100 / scale.coerceAtLeast(1)
        val bars = when { pct > 66 -> 3; pct > 33 -> 2; pct > 10 -> 1; else -> 0 }
        val read = messagesReadFrames > 0
        val cleared = missedClearedFrames > 0
        if (read) messagesReadFrames--
        if (cleared) missedClearedFrames--
        return Frames.status(bars, plugged, signalBars = 3, messagesRead = read, missedCleared = cleared)
    }
}
