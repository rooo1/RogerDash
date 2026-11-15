package app.roger.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

class NavParserTest {
    private val now = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 40) }

    @Test fun distanceTitleAndInstruction() {
        val s = NavParser.parse("350 m", "Turn left onto MG Road", "12 min · 5.2 km · ETA 12:52 pm", now)!!
        assertEquals(Frames.Icon.LEFT, s.icon)
        assertEquals(350, s.toTurnM)
        assertEquals(5200, s.remainingM)
        assertEquals(12, s.eta!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(52, s.eta!!.get(Calendar.MINUTE))
        assertEquals("Turn left onto MG Road", s.instruction)
    }

    @Test fun kmAndMinutesOnly() {
        val s = NavParser.parse("1.2 km", "Slight right toward NH 48", "1 hr 5 min (48 km)", now)!!
        assertEquals(Frames.Icon.SLIGHT_RIGHT, s.icon)
        assertEquals(1200, s.toTurnM)
        assertEquals(48000, s.remainingM)
        assertEquals(13, s.eta!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, s.eta!!.get(Calendar.MINUTE))
    }

    @Test fun roundaboutExit() {
        assertEquals(Frames.Icon.ROUNDABOUT_EXIT_1 + 2, NavParser.icon("At the roundabout, take the 3rd exit onto Ring Rd"))
        assertEquals(Frames.Icon.ROUNDABOUT_EXIT_1 + 1, NavParser.icon("At the roundabout, take the second exit"))
    }

    @Test fun otherManeuvers() {
        assertEquals(Frames.Icon.RIGHT, NavParser.icon("Turn right"))
        assertEquals(Frames.Icon.U_TURN, NavParser.icon("Make a U-turn"))
        assertEquals(Frames.Icon.KEEP_LEFT, NavParser.icon("Keep left to stay on NH 44"))
        assertEquals(Frames.Icon.SHARP_RIGHT, NavParser.icon("Sharp right onto Church St"))
        assertEquals(Frames.Icon.OFF_RAMP_RIGHT, NavParser.icon("Take the exit on the right toward Airport"))
        assertEquals(Frames.Icon.ARRIVE, NavParser.icon("Your destination is on the left"))
        assertEquals(Frames.Icon.STRAIGHT, NavParser.icon("Continue onto Hosur Road"))
    }

    @Test fun reroutingStatus() {
        val s = NavParser.parse("Rerouting…", null, null, now)
        assertNotNull(s)
        assertEquals('2', s!!.status)
    }
}
