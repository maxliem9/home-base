package com.homebase.android

import com.homebase.android.data.model.AbsSettingsDto
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.CustomHolidayDto
import com.homebase.android.ui.abwesenheit.buildContext
import com.homebase.android.ui.abwesenheit.isWorkdayFor
import com.homebase.android.ui.abwesenheit.personDay
import com.homebase.android.ui.abwesenheit.wouldWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom-holiday rendering + work-credit semantics (#52, the Android pendant of #51).
 * Mirrors the web core.test.ts cases: a half custom holiday stays a (half) work day, a whole
 * one is a full holiday, a statutory holiday on the same date wins, and a snapshot without the
 * customHolidays list never throws. All chosen dates in 2026 fall on a Thursday (a workday), so
 * the weekend never masks the holiday result.
 */
class AbsenceCustomHolidayTest {

    private val user = "alice"

    private fun state(vararg holidays: CustomHolidayDto) = AbsenceStateDto(
        users = listOf(user),
        settings = listOf(AbsSettingsDto(user, 2026, "BE", 30.0, 0.0, "2026-03-31", 15)),
        customHolidays = holidays.toList(),
    )

    @Test
    fun `half custom holiday marks the day yet stays workable`() {
        val s = state(CustomHolidayDto("h1", 12, 24, half = true, label = "Heiligabend"))
        val day = personDay(buildContext(s, 2026, listOf(user)), user, "2026-12-24")
        assertEquals("Heiligabend", day.holiday)
        assertTrue(day.holidayHalf)
        assertTrue(wouldWork(day)) // the other half is still a work day
        assertTrue(isWorkdayFor(s, user, "2026-12-24"))
    }

    @Test
    fun `whole custom holiday is a full non-working holiday`() {
        val s = state(CustomHolidayDto("h1", 12, 31, half = false, label = "Silvester"))
        val day = personDay(buildContext(s, 2026, listOf(user)), user, "2026-12-31")
        assertEquals("Silvester", day.holiday)
        assertFalse(day.holidayHalf)
        assertFalse(wouldWork(day))
        assertFalse(isWorkdayFor(s, user, "2026-12-31"))
    }

    @Test
    fun `a statutory holiday on the same date wins over a custom one`() {
        // 2026-01-01 (Neujahr) is statutory everywhere; a custom half-day on 01-01 must not override it.
        val s = state(CustomHolidayDto("h1", 1, 1, half = true, label = "Eigenes Neujahr"))
        val day = personDay(buildContext(s, 2026, listOf(user)), user, "2026-01-01")
        assertEquals("Neujahr", day.holiday)
        assertFalse(day.holidayHalf) // statutory wins → full day, no ½
        assertFalse(wouldWork(day))
    }

    @Test
    fun `a snapshot without custom holidays resolves to no holiday`() {
        val day = personDay(buildContext(state(), 2026, listOf(user)), user, "2026-12-24")
        assertNull(day.holiday)
        assertFalse(day.holidayHalf)
        assertTrue(wouldWork(day))
    }

    @Test
    fun `a custom holiday recurs every year, matched by month and day`() {
        // The MM-DD keying (not a full date) is the whole point: the same fixed-date holiday
        // must resolve in any year. Settings inherit from 2026 to 2027 (nearest-year).
        val s = state(CustomHolidayDto("h1", 12, 24, half = true, label = "Heiligabend"))
        val day = personDay(buildContext(s, 2027, listOf(user)), user, "2027-12-24")
        assertEquals("Heiligabend", day.holiday)
        assertTrue(day.holidayHalf)
    }
}
