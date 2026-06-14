package com.homebase.android

import com.homebase.android.ui.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

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
    fun `etaShortLabel shows the projected end while in the future (German)`() {
        val now = Instant.parse("2026-06-10T12:00:00Z")
        val label = Format.etaShortLabel("2026-06-10T15:30:00Z", now, Locale.GERMAN)
        // rendered in the device zone — assert structure, not the zone-shifted time
        assertEquals(true, label!!.startsWith("bis ca. "))
        assertEquals(true, Regex("bis ca\\. \\d{2}:\\d{2}").matches(label))
    }

    @Test
    fun `etaShortLabel flips to Soll erreicht once passed (German)`() {
        val now = Instant.parse("2026-06-10T16:00:00Z")
        assertEquals("Soll erreicht", Format.etaShortLabel("2026-06-10T15:30:00Z", now, Locale.GERMAN))
        // boundary: exactly now counts as reached
        assertEquals("Soll erreicht", Format.etaShortLabel("2026-06-10T16:00:00Z", now, Locale.GERMAN))
    }

    // 14 June 2026 is a Sunday — the issue's worked example "Sonntag, 14. Juni" → "Sunday, 14 June".
    private val sunday = LocalDate.of(2026, 6, 14)

    @Test
    fun `longWeekdayDate follows the given locale`() {
        assertEquals("Sonntag, 14. Juni", Format.longWeekdayDate(sunday, Locale.GERMAN))
        assertEquals("Sunday, 14 June", Format.longWeekdayDate(sunday, Locale.ENGLISH))
    }

    @Test
    fun `shortDate follows the given locale`() {
        // current-year date → no year suffix; the day/month order and month name localize
        val thisYear = LocalDate.now().withMonth(6).withDayOfMonth(3)
        assertEquals("3. Juni", Format.shortDate(thisYear, Locale.GERMAN))
        assertEquals("3 June", Format.shortDate(thisYear, Locale.ENGLISH))
    }

    @Test
    fun `etaShortLabel localizes under an English locale`() {
        val future = Instant.parse("2026-06-10T12:00:00Z")
        val label = Format.etaShortLabel("2026-06-10T15:30:00Z", future, Locale.ENGLISH)
        assertTrue(label!!.startsWith("until ~"))
        assertTrue(Regex("until ~\\d{2}:\\d{2}").matches(label))
        // and the past-due variant
        val past = Instant.parse("2026-06-10T16:00:00Z")
        assertEquals("Target reached", Format.etaShortLabel("2026-06-10T15:30:00Z", past, Locale.ENGLISH))
    }
}
