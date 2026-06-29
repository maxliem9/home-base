package com.homebase

import com.homebase.reminder.ReminderCandidate
import com.homebase.reminder.ReminderLogic
import com.homebase.reminder.ReminderLogic.Action
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure timing/decision tests for the todo reminder scheduler (#429 Phase 2a). */
class ReminderLogicTest {

    private fun candidate(date: String, time: String, lead: Int? = null, title: String = "Zahnarzt", assignee: String? = null) =
        ReminderCandidate(title, assignee, LocalDate.parse(date), LocalTime.parse(time), lead)

    @Test
    fun `fire moment subtracts the lead from the due time`() {
        assertEquals(LocalDateTime.parse("2026-07-01T14:00"), ReminderLogic.fireMoment(candidate("2026-07-01", "14:30", lead = 30)))
        // no lead → exactly at the due time
        assertEquals(LocalDateTime.parse("2026-07-01T14:30"), ReminderLogic.fireMoment(candidate("2026-07-01", "14:30")))
        // a lead can roll the fire moment into the previous day
        assertEquals(LocalDateTime.parse("2026-06-30T23:30"), ReminderLogic.fireMoment(candidate("2026-07-01", "00:00", lead = 30)))
    }

    @Test
    fun `decide waits before, fires inside the window, retires when stale`() {
        val c = candidate("2026-07-01", "14:30") // fires at 14:30
        assertEquals(Action.WAIT, ReminderLogic.decide(c, LocalDateTime.parse("2026-07-01T14:29")))
        assertEquals(Action.FIRE, ReminderLogic.decide(c, LocalDateTime.parse("2026-07-01T14:30")))
        assertEquals(Action.FIRE, ReminderLogic.decide(c, LocalDateTime.parse("2026-07-01T20:00"))) // within 12h catch-up
        assertEquals(Action.RETIRE, ReminderLogic.decide(c, LocalDateTime.parse("2026-07-02T03:00"))) // > 12h late
    }

    @Test
    fun `quiet hours window, including wrap past midnight`() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(7, 0)
        assertTrue(ReminderLogic.inQuietHours(LocalTime.of(23, 0), start, end))
        assertTrue(ReminderLogic.inQuietHours(LocalTime.of(2, 0), start, end))
        assertTrue(ReminderLogic.inQuietHours(LocalTime.of(22, 0), start, end)) // inclusive start
        assertFalse(ReminderLogic.inQuietHours(LocalTime.of(7, 0), start, end)) // exclusive end
        assertFalse(ReminderLogic.inQuietHours(LocalTime.of(12, 0), start, end))
        // a non-wrapping window
        assertTrue(ReminderLogic.inQuietHours(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)))
        // incomplete / equal bounds = no quiet hours
        assertFalse(ReminderLogic.inQuietHours(LocalTime.of(23, 0), start, null))
        assertFalse(ReminderLogic.inQuietHours(LocalTime.of(23, 0), null, end))
        assertFalse(ReminderLogic.inQuietHours(LocalTime.of(23, 0), start, start))
    }

    @Test
    fun `message renders the time and optional assignee`() {
        assertEquals("🔔 Erinnerung: Zahnarzt — fällig 14:30", ReminderLogic.message(candidate("2026-07-01", "14:30")))
        assertEquals("🔔 Erinnerung: Müll — fällig 07:05 · bob", ReminderLogic.message(candidate("2026-07-01", "07:05", title = "Müll", assignee = "bob")))
    }
}
