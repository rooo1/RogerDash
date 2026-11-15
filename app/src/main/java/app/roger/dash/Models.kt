package app.roger.dash

/**
 * The vehicle the rider chose in setup, and everything that differs between models, kept in one place.
 *
 * What is shared by all Suzuki Ride Connect dashes comes from Suzuki's own app: the Bluetooth link,
 * the 30-byte frames, 0–6 fuel bars, contact names of up to 19 letters. What is only known from a real
 * bike (text length on screen, which icons show, what bike-data byte 25 means) is known for the
 * V-Strom 250 SX alone, so other models get safe values and are marked "not tested yet".
 *
 * Add a model only after it has been tried on a real bike, or mark it [tested] = false.
 */
enum class DashModel(
    val id: String,
    val title: String,
    /** Tried on a real bike: arrows, pop-ups, calls and messages showed as expected. */
    val tested: Boolean,
    /** Has a Suzuki Ride Connect dash to talk to over Bluetooth. */
    val suzuki: Boolean,
    /** Tank size in litres, when known for the model (else the rider types it in). */
    val tankL: Double?,
    /** Fuel bars on the dash gauge (0 = no gauge: range from the fuel log). */
    val bars: Int,
    /** Expected km/l until fill-ups measure it, when known. */
    val typicalKmpl: Double?,
    /** Characters a dash pop-up shows. 12 on the V-Strom; unknown elsewhere, so the same short limit. */
    val textMax: Int,
    /** Bike-data byte 25 is engine temperature (checked on the V-Strom only; Suzuki's app reads it differently). */
    val engineTemp: Boolean,
) {
    VSTROM_250_SX("vstrom250sx", "Suzuki V-Strom 250 SX", tested = true, suzuki = true,
        tankL = 12.0, bars = 6, typicalKmpl = 30.0, textMax = 12, engineTemp = true),

    /** Any other Suzuki with a Ride Connect dash: same protocol (from Suzuki's app), never tried by us. */
    OTHER_SUZUKI("othersuzuki", "Other Suzuki", tested = false, suzuki = true,
        tankL = null, bars = 6, typicalKmpl = null, textMax = 12, engineTemp = false),

    /** Ride log and fuel tracker only, from the phone's GPS. */
    NONE("none", "Not a Suzuki", tested = false, suzuki = false,
        tankL = null, bars = 0, typicalKmpl = null, textMax = 12, engineTemp = false);

    /** What the screen calls it: "V-Strom 250 SX", "Other Suzuki (not tested yet)". */
    val label: String get() = when {
        !suzuki -> title
        tested -> title.removePrefix("Suzuki ")
        else -> "$title (not tested yet)"
    }

    /** Name for the vehicle this model creates. */
    val vehicleName: String get() = when (this) {
        VSTROM_250_SX -> "V-Strom 250 SX"
        OTHER_SUZUKI -> "My Suzuki"
        NONE -> "My bike"
    }

    companion object {
        fun of(id: String?): DashModel? = values().firstOrNull { it.id == id }

        /**
         * Older Access and Burgman Street dashes (Bluetooth names "SAS…", e.g. SAS01…) use an inverted
         * checksum (from Suzuki's app). RogerDash doesn't support those yet.
         */
        fun legacyDash(btName: String?): Boolean = btName != null && btName.length == 12 && btName.substring(1, 3) == "AS"
    }
}
