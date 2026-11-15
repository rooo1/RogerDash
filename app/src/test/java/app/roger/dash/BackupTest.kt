package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val sample = """
        {"app":"RogerDash","format":1,"made":1794700000000,
         "prefs":{"vehicles":{"t":"s","v":"[{\"id\":\"main\",\"name\":\"V-Strom 250 SX\",\"dash\":true},{\"id\":\"v2\",\"name\":\"Scooter\",\"dash\":false}]"},
                  "dashAvg":{"t":"f","v":32.8},"multiVehicle":{"t":"b","v":true},"lastOdo":{"t":"i","v":4671}},
         "files":{"trips/1.json":"{}","trips/2.json":"{}","parked.json":"{}"}}
    """.trimIndent()

    @Test fun readsSummary() {
        val (_, s) = Backup.read(sample)
        assertEquals(2, s.rides)
        assertEquals(2, s.vehicles)
        assertFalse(s.hasFuel)
        assertEquals(1794700000000, s.made)
    }

    @Test fun rejectsOtherFiles() {
        assertTrue(runCatching { Backup.read("""{"app":"Other","format":1,"prefs":{},"files":{}}""") }.isFailure)
        assertTrue(runCatching { Backup.read("""{"app":"RogerDash","format":99,"prefs":{},"files":{}}""") }.isFailure)
    }
}
