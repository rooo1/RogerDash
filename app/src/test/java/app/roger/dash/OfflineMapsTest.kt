package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tile maths for saved map areas, and OpenFreeMap's 3,000-tile limit per download. */
class OfflineMapsTest {
    private val pune = LatLon(18.5204, 73.8567)

    @Test fun tileOfAKnownPlace() {
        // Zoom 14 tile holding Pune centre (slippy-map formula).
        assertEquals(11553, OfflineMaps.tileX(pune.lon))
        assertEquals(7334, OfflineMaps.tileY(pune.lat))
        val b = OfflineMaps.bounds(OfflineMaps.Tile(11553, 7334))
        assertTrue(pune.lat in b[0]..b[2] && pune.lon in b[1]..b[3])
    }

    @Test fun areaSizesFitTheLimit() {
        val small = OfflineMaps.around(pune, 10.0)
        val big = OfflineMaps.around(pune, 50.0)
        assertTrue(small.size in 50..200)        // ~2.2 km tiles: a 10 km circle is ~65–100 of them
        assertTrue(OfflineMaps.count(big) <= OfflineMaps.MAX_TILES)
        assertTrue(OfflineMaps.count(small) > small.size) // plus the tiles above, out to the whole world
    }

    @Test fun routeCorridor() {
        val mumbai = LatLon(19.0760, 72.8777)
        val tiles = OfflineMaps.along(listOf(mumbai, pune), 2.0) // ~120 km straight line
        // 120 km / 2.2 km per tile ≈ 55 tiles long, 2–3 wide.
        assertTrue("${tiles.size}", tiles.size in 100..300)
        assertTrue(OfflineMaps.Tile(OfflineMaps.tileX(pune.lon), OfflineMaps.tileY(pune.lat)) in tiles)
        assertTrue(OfflineMaps.count(tiles) <= OfflineMaps.MAX_TILES)
    }

    @Test fun countIncludesEveryZoomOnce() {
        val one = setOf(OfflineMaps.Tile(11553, 7334))
        assertEquals(15, OfflineMaps.count(one)) // zoom 0..14, one tile each
    }
}
