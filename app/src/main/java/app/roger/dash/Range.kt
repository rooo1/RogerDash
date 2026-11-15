package app.roger.dash

import kotlin.math.roundToInt

/**
 * Estimated range from the dash's fuel bars and the rider's real mileage.
 * Suzuki dashes send 0–6 fuel bars (from Suzuki's app); tank size comes from the vehicle (V-Strom 250 SX: 12 L, each bar ≈ 2 L).
 */
object Range {
    /** Fuel bars on a Suzuki dash gauge, when the vehicle doesn't say. */
    const val BARS = 6

    /** How much to trust a range estimate: how its mileage was found. */
    enum class Confidence(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

    /**
     * An estimate, never a measurement: fuel bars × mileage. [plusMinusKm]: half a bar's worth either way,
     * since a bar stays lit from full down to empty.
     */
    data class Estimate(val km: Int, val kmPerL: Double, val basis: String, val confidence: Confidence = Confidence.LOW, val plusMinusKm: Int = 0)

    data class Mileage(val kmPerL: Double, val basis: String, val confidence: Confidence)

    /** The bike's own average (sent by the dash, or typed in by the rider); set by [DashHub]. Suzuki only. */
    var fromDash: () -> Pair<Double, String>? = { null }

    /** The vehicle chosen now, and the Suzuki whose dash sends fuel bars; set by [DashHub]. */
    var current: () -> Vehicle = { Vehicle.defaultFor(DashModel.VSTROM_250_SX) }
    var dashBike: () -> Vehicle = { Vehicle.defaultFor(DashModel.VSTROM_250_SX) }

    fun plausible(kmpl: Double?) = kmpl != null && kmpl in 10.0..80.0

    /**
     * Mileage of [v], best source first: measured full-tank to full-tank (2+ times: high confidence), the dash's
     * average typed in (medium, Suzuki only), one full-tank measurement (medium), fuel bars used on rides (low),
     * the expected figure from the vehicle's settings (low).
     */
    fun mileageOf(store: TripStore, v: Vehicle = current()): Mileage {
        val e = Economy.of(store.fuel(v.id))
        val measured = e.kmPerL?.takeIf { plausible(it) }
        if (measured != null && e.intervals >= 2) return Mileage(measured, "measured over ${e.intervals} full tanks", Confidence.HIGH)
        if (v.dash) fromDash()?.let { return Mileage(it.first, it.second, Confidence.MEDIUM) }
        if (measured != null) return Mileage(measured, "measured over 1 full tank", Confidence.MEDIUM)
        if (v.dash && v.bars > 0) {
            val withFuel = store.trips(v.id).filter { it.fuelStart != null && it.fuelEnd != null }
            val bars = withFuel.sumOf { (it.fuelStart!! - it.fuelEnd!!).coerceAtLeast(0) }
            val km = withFuel.sumOf { it.distanceKm }
            if (bars >= 3 && km > 100) return Mileage(km / (bars * v.tankL / v.bars), "from fuel bars used on your rides", Confidence.LOW)
        }
        return Mileage(v.typicalKmpl, "expected for your ${v.name}; log full-tank fill-ups for the real figure", Confidence.LOW)
    }

    fun mileage(store: TripStore): Pair<Double, String> = mileageOf(store).let { it.kmPerL to it.basis }

    /** From the Suzuki's fuel bars. */
    fun estimate(bars: Int?, store: TripStore): Estimate? {
        val v = dashBike()
        val n = v.bars.takeIf { it > 0 } ?: BARS
        val b = bars?.takeIf { it in 0..n } ?: return null
        val m = mileageOf(store, v)
        // A bar shows while there's fuel left in it, so count each lit bar as about half full on average.
        val litres = ((b - 0.5).coerceAtLeast(0.25) / n) * v.tankL
        return Estimate((litres * m.kmPerL).roundToInt(), m.kmPerL, m.basis, m.confidence, (v.tankL / n / 2 * m.kmPerL).roundToInt())
    }

    /**
     * GPS-only vehicle (no fuel gauge): tank × mileage, minus the km ridden since the last full tank
     * ([ridingKm]: the ride going on now). Null until a full tank is logged. Less sure than the
     * mileage alone: a ride that wasn't recorded isn't counted.
     */
    fun fromLog(store: TripStore, v: Vehicle, ridingKm: Double = 0.0): Estimate? {
        val full = store.fuel(v.id).filter { it.full }.maxByOrNull { it.time } ?: return null
        val since = store.trips(v.id).filter { it.start > full.time }.sumOf { it.distanceKm } + ridingKm
        val m = mileageOf(store, v)
        val tankKm = v.tankL * m.kmPerL
        val km = (tankKm - since).coerceAtLeast(0.0)
        val conf = when (m.confidence) { Confidence.HIGH -> Confidence.MEDIUM; else -> Confidence.LOW }
        return Estimate(km.roundToInt(), m.kmPerL, "full tank on ${Fmt.day(full.time)}, ${Fmt.km(since)} ridden since; ${m.basis}", conf,
            (since * 0.1 + tankKm * 0.05).roundToInt())
    }

    /** Range of [v] now: fuel bars for the Suzuki, km since the last full tank for anything else. */
    fun now(store: TripStore, v: Vehicle, bars: Int?, ridingKm: Double = 0.0): Estimate? =
        if (v.dash) estimate(bars, store) else fromLog(store, v, ridingKm)

    /** Fuel left as gauge bars (0–6) for a GPS-only vehicle, to draw the same gauge. */
    fun barsOf(e: Estimate, v: Vehicle): Int = ((e.km / (v.tankL * e.kmPerL)) * 6).roundToInt().coerceIn(0, 6)

    /** Fuel and money for a ride of [km]: litres, ₹ (null without a logged price), and how many refuels on the way. */
    data class TripFuel(val litres: Double, val cost: Double?, val refuels: Int, val firstRefuelByKm: Int?)

    fun forTrip(km: Double, rangeNowKm: Int?, store: TripStore, v: Vehicle = current()): TripFuel {
        val m = mileageOf(store, v)
        val litres = km / m.kmPerL
        val price = Economy.pricePerL(store.fuel(v.id)) ?: Economy.pricePerL(store.fuel())
        // Refill with ~1/6 of the tank left.
        val tankKm = v.tankL * 5 / 6 * m.kmPerL
        val now = (rangeNowKm ?: tankKm.toInt()) - LOW_KM / 2
        val refuels = if (km <= now) 0 else 1 + ((km - now) / tankKm).toInt()
        return TripFuel(litres, price?.let { it * litres }, refuels, if (refuels > 0) now.coerceAtLeast(0) else null)
    }

    /** Below this the dash text turns into a warning. */
    const val LOW_KM = 50

    /**
     * The dash shows only 12 characters of a message, upper-case, and has no "~"
     * (bike test 2026-11-14: "Range ~150 km" showed as "RANGE 150 K"). So: "RANGE 150KM", "LOW FUEL 35K".
     */
    fun dashText(km: Int) = if (km < LOW_KM) "LOW FUEL ${km}K" else "RANGE ${km}KM"

    /** Route longer than the range: "REFUEL 120KM". */
    fun refuelText(km: Int) = "REFUEL ${km}KM"
}
