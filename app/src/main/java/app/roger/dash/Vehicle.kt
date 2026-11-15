package app.roger.dash

import org.json.JSONObject

/**
 * A bike or scooter the rider owns. [dash]: the Suzuki that talks to this app over Bluetooth (fuel bars,
 * odometer, turn arrows on its dash); every other vehicle is GPS-only: rides from the phone's GPS, range
 * from the km ridden since the last full tank.
 * Rides and fill-ups recorded before vehicles existed belong to [MAIN] (the Suzuki).
 */
data class Vehicle(
    val id: String,
    val name: String,
    val dash: Boolean,
    val tankL: Double,
    /** Expected km/l until fill-ups measure the real figure. */
    val typicalKmpl: Double,
    val bars: Int = if (dash) 6 else 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("dash", dash)
        .put("tankL", tankL).put("typicalKmpl", typicalKmpl).put("bars", bars)

    companion object {
        const val MAIN = "main"

        /**
         * The main vehicle for a [DashModel], with the model's figures where they're known. Otherwise
         * 10 L and 35 km/l stand in until the rider types theirs (setup asks; More → Edit) and fill-ups measure it.
         */
        fun defaultFor(m: DashModel) = Vehicle(MAIN, m.vehicleName, dash = m.suzuki,
            tankL = m.tankL ?: 10.0, typicalKmpl = m.typicalKmpl ?: 35.0, bars = m.bars)

        fun fromJson(o: JSONObject) = Vehicle(
            o.getString("id"), o.getString("name"), o.optBoolean("dash"), o.optDouble("tankL", 5.0),
            o.optDouble("typicalKmpl", 40.0), o.optInt("bars", if (o.optBoolean("dash")) 6 else 0),
        )
    }
}
