package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Range counting down between fuel bars (ride 2026-11-16: 3 bars = 164 km, then 98 km straight after a bar went out). */
class RangeTest {
    private val v = Vehicle.defaultFor(DashModel.VSTROM_250_SX) // 12 L, 6 bars: 2 L a bar
    private val kmpl = 32.8

    private fun km(a: Range.Anchor?, bars: Int, odo: Int) = Range.litresNow(a, bars, odo, kmpl, v) * kmpl

    @Test fun unknownStartIsHalfABar() {
        val a = Range.track(null, 3, 1000, v)
        assertFalse(a.atTop)
        assertEquals(164.0, km(a, 3, 1000), 1.0) // 2.5 bars × 2 L × 32.8
    }

    @Test fun countsDownBetweenBars() {
        val a = Range.track(null, 3, 1000, v)
        assertEquals(144.0, km(a, 3, 1020), 1.0) // 20 km later: no longer stuck at 164
    }

    @Test fun neverBelowTheLastLitBar() {
        val a = Range.track(null, 3, 1000, v)
        // 3 bars still lit after 60 km: at least 2 bars and a bit (4.2 L) are left.
        assertEquals(4.2 * kmpl, km(a, 3, 1060), 1.0)
    }

    @Test fun barGoingOutIsTheTopOfTheNextBar() {
        val a3 = Range.track(null, 3, 1000, v)
        val a2 = Range.track(a3, 2, 1020, v)
        assertTrue(a2.atTop)
        assertEquals(131.0, km(a2, 2, 1020), 1.0) // 4 L, not 1.5 bars = 98 km
        assertEquals(111.0, km(a2, 2, 1040), 1.0)
    }

    @Test fun sameBarsKeepsTheAnchorOverIgnitionOff() {
        val a2 = Range.track(Range.track(null, 3, 1000, v), 2, 1020, v)
        assertEquals(a2, Range.track(a2, 2, 1035, v))
        assertEquals(a2, Range.Anchor.load(a2.save()))
    }

    @Test fun refuelOrJumpStartsAgain() {
        val a2 = Range.track(Range.track(null, 3, 1000, v), 2, 1020, v)
        assertFalse(Range.track(a2, 5, 1030, v).atTop) // refuelled
        assertFalse(Range.track(a2, 0, 1100, v).atTop) // missed a bar while away
    }

    private fun ride(start: Long, odo: IntRange, bars: Pair<Int, Int>) = Trip(start, "", start, start + 1, (odo.last - odo.first).toDouble(),
        0, 0, odo.first, odo.last, bars.first, bars.second, "bike", vehicle = v.id)

    @Test fun barDropFoundInSavedRides() {
        val rides = listOf(
            ride(3, 4700..4707, 2 to 2),   // newest: stayed on 2
            ride(2, 4650..4690, 3 to 2),   // bar went out during this ride: about 4670
            ride(1, 4600..4640, 3 to 3),
        )
        val a = Range.fromRides(rides, 2, v)!!
        assertEquals(4670, a.odo)
        assertTrue(a.atTop)
        assertEquals(4.0 - 37 / kmpl, Range.litresNow(a, 2, 4707, kmpl, v), 0.01) // counted down 37 km since
    }

    @Test fun barDropBetweenRides() {
        val rides = listOf(ride(2, 4700..4710, 2 to 2), ride(1, 4650..4680, 3 to 3))
        assertEquals(4690, Range.fromRides(rides, 2, v)!!.odo)
    }

    @Test fun noDropInRidesMeansNoAnchor() {
        assertEquals(null, Range.fromRides(listOf(ride(1, 4650..4680, 6 to 2)), 2, v))
        assertEquals(null, Range.fromRides(listOf(ride(1, 4650..4680, 2 to 2)), 2, v))
    }

    @Test fun noBarsLitStillHasReserve() {
        assertTrue(km(Range.track(null, 0, 1000, v), 0, 1000) > 0)
    }
}
