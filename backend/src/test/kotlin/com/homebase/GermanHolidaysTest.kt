package com.homebase

import com.homebase.holidays.GermanHolidays
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Kotlin port (#31) to known calendar facts so it cannot drift from the
 * client-side implementation in web/src/components/abwesenheit/holidays.ts.
 */
class GermanHolidaysTest {

    @Test
    fun `easter matches known dates`() {
        assertEquals(LocalDate.of(2024, 3, 31), GermanHolidays.easter(2024))
        assertEquals(LocalDate.of(2025, 4, 20), GermanHolidays.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), GermanHolidays.easter(2026))
    }

    @Test
    fun `easter-relative holidays 2026`() {
        val be = GermanHolidays.holidays(2026, "BE")
        assertEquals("Karfreitag", be[LocalDate.of(2026, 4, 3)])
        assertEquals("Ostermontag", be[LocalDate.of(2026, 4, 6)])
        assertEquals("Christi Himmelfahrt", be[LocalDate.of(2026, 5, 14)])
        assertEquals("Pfingstmontag", be[LocalDate.of(2026, 5, 25)])
    }

    @Test
    fun `nationwide fixed holidays apply in every state`() {
        for (state in GermanHolidays.ALL_STATES) {
            val h = GermanHolidays.holidays(2026, state)
            assertEquals("Neujahr", h[LocalDate.of(2026, 1, 1)])
            assertEquals("Tag der Arbeit", h[LocalDate.of(2026, 5, 1)])
            assertEquals("Tag der Deutschen Einheit", h[LocalDate.of(2026, 10, 3)])
            assertEquals("1. Weihnachtstag", h[LocalDate.of(2026, 12, 25)])
            assertEquals("2. Weihnachtstag", h[LocalDate.of(2026, 12, 26)])
        }
    }

    @Test
    fun `state-specific holidays only apply in their states`() {
        // Frauentag: BE/MV only
        assertTrue(LocalDate.of(2026, 3, 8) in GermanHolidays.holidays(2026, "BE"))
        assertFalse(LocalDate.of(2026, 3, 8) in GermanHolidays.holidays(2026, "BY"))
        // Fronleichnam (Easter+60): BY yes, BE no
        assertEquals("Fronleichnam", GermanHolidays.holidays(2026, "BY")[LocalDate.of(2026, 6, 4)])
        assertFalse(LocalDate.of(2026, 6, 4) in GermanHolidays.holidays(2026, "BE"))
        // Reformationstag vs Allerheiligen split
        assertTrue(LocalDate.of(2026, 10, 31) in GermanHolidays.holidays(2026, "SN"))
        assertFalse(LocalDate.of(2026, 10, 31) in GermanHolidays.holidays(2026, "BY"))
        assertTrue(LocalDate.of(2026, 11, 1) in GermanHolidays.holidays(2026, "BY"))
        assertFalse(LocalDate.of(2026, 11, 1) in GermanHolidays.holidays(2026, "BE"))
    }

    @Test
    fun `buss- und bettag is the wednesday before nov 23, saxony only`() {
        assertEquals("Buß- und Bettag", GermanHolidays.holidays(2026, "SN")[LocalDate.of(2026, 11, 18)])
        assertEquals("Buß- und Bettag", GermanHolidays.holidays(2025, "SN")[LocalDate.of(2025, 11, 19)])
        assertFalse(LocalDate.of(2026, 11, 18) in GermanHolidays.holidays(2026, "BE"))
    }
}
