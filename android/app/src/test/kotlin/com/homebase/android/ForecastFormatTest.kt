package com.homebase.android

import com.homebase.android.ui.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for the Wochensoll formatting helpers (#55): the compact "h:mm"
 * Soll/Ist figure (mirrors the web's hm() incl. minute rounding and clamping)
 * and the short ETA suffix shown at running timers.
 */
class ForecastFormatTest {

    @Test
    fun `hoursMinutes formats whole and partial hours`() {
        assertEquals("38:00", Format.hoursMinutes(38 * 3600L))
        assertEquals("7:30", Format.hoursMinutes(7 * 3600L + 30 * 60L))
        assertEquals("0:00", Format.hoursMinutes(0))
    }

    @Test
    fun `hoursMinutes rounds to the nearest minute`() {
        assertEquals("0:01", Format.hoursMinutes(30)) // 30s rounds up
        assertEquals("0:00", Format.hoursMinutes(29)) // 29s rounds down
        assertEquals("1:00", Format.hoursMinutes(3599)) // 59:59 → 60 min
    }

    @Test
    fun `hoursMinutes clamps negative input`() {
        assertEquals("0:00", Format.hoursMinutes(-7200))
    }

    @Test
    fun `etaShortLabel is null without a forecast`() {
        assertNull(Format.etaShortLabel(null))
        assertNull(Format.etaShortLabel(""))
    }

    @Test
    fun `etaShortLabel shows the projected end while in the future`() {
        val now = Instant.parse("2026-06-10T12:00:00Z")
        val label = Format.etaShortLabel("2026-06-10T15:30:00Z", now)
        // rendered in the device zone — assert structure, not the zone-shifted time
        assertEquals(true, label!!.startsWith("bis ca. "))
        assertEquals(true, Regex("bis ca\\. \\d{2}:\\d{2}").matches(label))
    }

    @Test
    fun `etaShortLabel flips to Soll erreicht once passed`() {
        val now = Instant.parse("2026-06-10T16:00:00Z")
        assertEquals("Soll erreicht", Format.etaShortLabel("2026-06-10T15:30:00Z", now))
        // boundary: exactly now counts as reached
        assertEquals("Soll erreicht", Format.etaShortLabel("2026-06-10T16:00:00Z", now))
    }
}
