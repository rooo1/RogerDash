package app.roger.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent

/**
 * Android's one-tap "Turn on location?" box (Google Play services). Without Play services, a dialog that
 * opens Location settings instead. The answer comes back in the activity's onActivityResult([req]).
 */
object LocationOn {
    fun isOn(a: Activity) = a.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true

    /** [onAnswer] is called right away when it's already on (true), or when the rider says "Not now" to the fallback (false). */
    fun ask(a: Activity, req: Int, onAnswer: (Boolean) -> Unit) {
        val request = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000).build())
            .setAlwaysShow(true)
            .build()
        com.google.android.gms.location.LocationServices.getSettingsClient(a).checkLocationSettings(request)
            .addOnFailureListener { e ->
                if (e is com.google.android.gms.common.api.ResolvableApiException) {
                    runCatching { e.startResolutionForResult(a, req) }.onFailure { fallback(a, onAnswer) }
                } else fallback(a, onAnswer)
            }
            .addOnSuccessListener { onAnswer(true) }
    }

    private fun fallback(a: Activity, onAnswer: (Boolean) -> Unit) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Location is off")
            .setMessage("RogerDash needs Location for navigation, ride maps and finding places near you.")
            .setPositiveButton("Turn on") { _, _ -> a.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setNegativeButton("Not now") { _, _ -> onAnswer(false) }
            .show()
    }
}
