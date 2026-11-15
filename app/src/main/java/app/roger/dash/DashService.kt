package app.roger.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the dashboard connected and records rides with the screen off.
 * GPS runs only while a ride is being recorded.
 */
@SuppressLint("MissingPermission")
class DashService : Service(), LocationListener {

    companion object {
        private const val CHANNEL = "dash"
        private const val ID = 1
        private const val ACTION_STOP = "app.roger.dash.STOP"
        @Volatile var running = false
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DashService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_STOP))

        private const val ALERTS = "alerts"

        /** A heads-up notification (e.g. low fuel), separate from the ongoing one. */
        fun alert(ctx: Context, title: String, text: String) = runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Reminders (fuel)", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(2, Notification.Builder(ctx, ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
                .build())
        }
    }

    private var gpsOn = false
    private var shown = ""
    private val update: () -> Unit = { notifyState() }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        DashHub.init(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "RogerDash", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasLocation()) { type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION; locType = true }
        try {
            startForeground(ID, notification(), type)
        } catch (e: Exception) {
            DashHub.onLog("Can't run in the background: $e")
            stopSelf()
            return
        }
        running = true
        DashHub.onRideChanged = { updateGps() }
        DashHub.addListener(update)
        updateGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DashHub.stopRide()
            DashHub.disconnectBike()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        DashHub.removeListener(update)
        DashHub.onRideChanged = null
        stopGps()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ───────────── GPS ─────────────

    private fun updateGps() = if (DashHub.gpsWanted) startGps() else stopGps()

    private var locType = false

    private fun startGps() {
        if (gpsOn || !hasLocation()) return
        if (!locType) {
            // Started before location was allowed: add the location type so GPS keeps working with the screen off.
            runCatching {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                locType = true
            }
        }
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            DashHub.onLog("Location is off: this ride uses the bike's data only (no route map)")
            return
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        gpsOn = true
    }

    private fun stopGps() {
        if (!gpsOn) return
        getSystemService(LocationManager::class.java).removeUpdates(this)
        gpsOn = false
    }

    override fun onLocationChanged(location: Location) = DashHub.onLocation(location)

    // ───────────── notification ─────────────

    private fun text(): String {
        val r = DashHub.ride
        val n = DashHub.nav
        return when {
            n != null && DashHub.navigator != null -> "${n.instruction} · ${n.toTurnM?.let { Frames.distance(it).let { d -> d.first.trimStart('0').ifEmpty { "0" } + (if (d.second == 'K') " km" else " m") } } ?: ""}"
            r != null -> "Riding · ${Fmt.km(r.distanceKm)} · ${Fmt.dur(r.elapsedSec)}"
            DashHub.connected -> "Connected to your bike"
            else -> DashHub.state
        }
    }

    private fun notifyState() {
        val t = text()
        if (t == shown) return
        shown = t
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, DashService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("RogerDash")
            .setContentText(text().also { shown = it })
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
