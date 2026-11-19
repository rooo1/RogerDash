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
     * An estimate, never a measurement: fuel bars × mileage, counted down by km ridden. [plusMinusKm]: half a
     * bar's worth either way (a bar stays lit from full down to empty), a quarter once a bar was seen going out.
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

    /**
     * Fuel in the tank at a known point: [litres] when the dash showed [bars] at odometer [odo] km. [atTop]: seen
     * the moment a bar went out, so the fuel was right at the top of the bar now showing; otherwise somewhere
     * inside it (counted as half full).
     */
    data class Anchor(val bars: Int, val odo: Int, val litres: Double, val atTop: Boolean) {
        fun save() = "$bars,$odo,$litres,$atTop"
        companion object {
            fun load(s: String?): Anchor? = s?.split(',')?.takeIf { it.size == 4 }?.let {
                Anchor(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null,
                    it[2].toDoubleOrNull() ?: return null, it[3] == "true")
            }
        }
    }

    /** The last [Anchor] and the bike's odometer now (live, or last seen); set by [DashHub]. */
    var anchor: () -> Anchor? = { null }
    var odo: () -> Int? = { null }

    private fun barsOn(v: Vehicle) = v.bars.takeIf { it > 0 } ?: BARS

    /**
     * New anchor after the dash sent [bars] at [odo]. A bar going out (one less than before) is the one moment
     * the fuel level is known well: right at the top of the new bar. Same bars: keep counting from the old anchor.
     * More bars (refuelled), a jump of 2+ bars, or an odometer that went backwards: start again at half a bar.
     */
    fun track(prev: Anchor?, bars: Int, odo: Int, v: Vehicle): Anchor {
        val perBar = v.tankL / barsOn(v)
        if (prev != null && odo >= prev.odo) {
            if (bars == prev.bars) return prev
            if (bars == prev.bars - 1) return Anchor(bars, odo, bars * perBar, atTop = true)
        }
        return Anchor(bars, odo, (bars - 0.5).coerceAtLeast(0.25) * perBar, atTop = false)
    }

    /**
     * Where the last bar went out, worked out from saved rides (each keeps its start/end odometer and bars), for
     * when there's no live [Anchor] yet: e.g. the ride before this app version. Newest ride first, walking back
     * while [bars] stayed lit:
     * - a ride that started on one more bar and ended on [bars]: the bar went out during it, so the middle of it;
     * - a ride that ended on one more bar, with the next one starting on [bars]: between them, so the middle of the gap.
     * Null if the bars were never seen going out, or the rides don't have the odometer.
     */
    fun fromRides(trips: List<Trip>, bars: Int, v: Vehicle): Anchor? {
        val perBar = v.tankL / barsOn(v)
        fun top(odo: Int) = Anchor(bars, odo, bars * perBar, atTop = true)
        var later: Trip? = null
        for (t in trips.filter { it.fuelStart != null && it.fuelEnd != null }.sortedByDescending { it.start }) {
            val s = t.fuelStart!!; val e = t.fuelEnd!!
            when {
                e == bars + 1 && later != null -> {
                    val a = t.endOdo ?: return null; val b = later.startOdo ?: return null
                    return if (b >= a) top((a + b) / 2) else null
                }
                e != bars -> return null // refuelled, or bars missing in between
                s == bars + 1 -> {
                    val a = t.startOdo ?: return null; val b = t.endOdo ?: return null
                    return if (b >= a) top((a + b) / 2) else null
                }
                s != bars -> return null
            }
            later = t
        }
        return null
    }

    /**
     * Litres in the tank now: the anchor's litres minus fuel used on the km ridden since, but never outside what
     * the [bars] lit allow (above the bottom of the last lit bar, at most the top of it).
     */
    fun litresNow(a: Anchor?, bars: Int, odoNow: Int?, kmPerL: Double, v: Vehicle): Double {
        val perBar = v.tankL / barsOn(v)
        val low = (bars - 1).coerceAtLeast(0) * perBar + (if (bars > 0) 0.1 * perBar else 0.0)
        val high = if (bars > 0) bars * perBar else 0.5 * perBar // no bars lit: some reserve is still there
        val start = a?.takeIf { it.bars == bars }
        if (start == null || odoNow == null || odoNow < start.odo) return (bars - 0.5).coerceAtLeast(0.25) * perBar
        return (start.litres - (odoNow - start.odo) / kmPerL).coerceIn(minOf(low, high), high)
    }

    /**
     * From the Suzuki's fuel bars, counted down by the km ridden since the last bar went out: the bars alone only
     * change every ~2 L (~60 km), so between them the range would sit still and then jump a whole bar.
     */
    fun estimate(bars: Int?, store: TripStore): Estimate? {
        val v = dashBike()
        val n = barsOn(v)
        val b = bars?.takeIf { it in 0..n } ?: return null
        val m = mileageOf(store, v)
        val a = anchor()?.takeIf { it.bars == b } ?: fromRides(store.trips(v.id), b, v)
        val litres = litresNow(a, b, odo(), m.kmPerL, v)
        // Known from a bar going out: about a quarter bar either way; otherwise half a bar.
        val plusMinus = v.tankL / n * (if (a?.atTop == true) 0.25 else 0.5) * m.kmPerL
        val basis = if (a?.atTop == true) "${m.basis}; counted down from when bar ${b + 1} went out" else m.basis
        return Estimate((litres * m.kmPerL).roundToInt(), m.kmPerL, basis, m.confidence, plusMinus.roundToInt())
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
