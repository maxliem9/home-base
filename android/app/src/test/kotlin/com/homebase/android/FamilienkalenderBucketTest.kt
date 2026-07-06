package com.homebase.android

import com.homebase.android.data.model.AbsenceDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.KitaClosureDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.ui.familienkalender.FamilienkalenderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure tests for the Familienkalender grid + bucket logic (#435) — the only non-trivial logic in the
 * read-only view. No coroutines/Android: just the [FamilienkalenderUiState] computed properties.
 */
class FamilienkalenderBucketTest {

    private fun todo(id: String, date: String?, status: String = "PLANNED", assignees: List<String> = emptyList()) =
        TodoDto(id = id, title = "T-$id", status = status, dueDate = date, assignees = assignees, createdBy = "alice", createdAt = "2026-06-01T08:00:00Z", updatedAt = "2026-06-01T08:00:00Z")

    private fun event(id: String, date: String, allDay: Boolean, start: String? = null) =
        CalendarEventDto(id = id, title = "E-$id", date = date, allDay = allDay, startTime = start, createdBy = "alice", createdAt = "2026-06-01T08:00:00Z")

    private fun meal(id: String, date: String, slot: String) =
        MealPlanEntryDto(id = id, date = date, slot = slot, dishTitle = "M-$id", createdBy = "alice", createdAt = "2026-06-01T08:00:00Z")

    @Test
    fun `gridDays spans whole Monday-to-Sunday weeks covering the month`() {
        val state = FamilienkalenderUiState(monthAnchor = LocalDate.of(2026, 6, 1))
        val days = state.gridDays
        assertEquals("grid must be whole weeks", 0, days.size % 7)
        assertEquals("starts on a Monday", DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertEquals("ends on a Sunday", DayOfWeek.SUNDAY, days.last().dayOfWeek)
        // every day of June is present
        (1..30).forEach { d ->
            assertTrue("June $d must be in the grid", days.contains(LocalDate.of(2026, 6, d)))
        }
        // the span brackets the month
        assertTrue(days.first().isBefore(LocalDate.of(2026, 6, 1)) || days.first().isEqual(LocalDate.of(2026, 6, 1)))
        assertTrue(days.last().isAfter(LocalDate.of(2026, 6, 30)) || days.last().isEqual(LocalDate.of(2026, 6, 30)))
    }

    @Test
    fun `gridDays trims a trailing 6th week that is entirely next month`() {
        // February 2027 starts on a Monday and has 28 days → exactly 4 weeks, no spillover. The
        // raw 6-week build must be trimmed to weeks that actually touch the month.
        val state = FamilienkalenderUiState(monthAnchor = LocalDate.of(2027, 2, 1))
        val days = state.gridDays
        assertEquals(28, days.size)
        assertEquals(LocalDate.of(2027, 2, 1), days.first())
        assertEquals(LocalDate.of(2027, 2, 28), days.last())
        // no day belongs to a different month
        assertTrue(days.all { it.monthValue == 2 && it.year == 2027 })
    }

    @Test
    fun `gridDays handles a December-to-January year boundary`() {
        // Dec 2026 spills into Jan 2027; the year-aware filter must keep December's weeks and not
        // mistake a January day for the wrong month (a month-only check would).
        val state = FamilienkalenderUiState(monthAnchor = LocalDate.of(2026, 12, 1))
        val days = state.gridDays
        assertEquals(0, days.size % 7)
        assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, days.last().dayOfWeek)
        (1..31).forEach { d -> assertTrue(days.contains(LocalDate.of(2026, 12, d))) }
        // leading/trailing spill is from the adjacent months/years, never December of another year
        assertTrue(days.none { it.monthValue == 12 && it.year != 2026 })
    }

    @Test
    fun `buckets exclude done todos and key by due date`() {
        val state = FamilienkalenderUiState(
            monthAnchor = LocalDate.of(2026, 6, 1),
            todos = listOf(
                todo("open", "2026-06-10"),
                todo("done", "2026-06-10", status = "DONE"),
                todo("nodate", null),
            ),
        )
        val day = state.buckets["2026-06-10"]
        assertNotNull(day)
        assertEquals(1, day!!.todos.size)
        assertEquals("open", day.todos.first().id)
    }

    @Test
    fun `events sort all-day first then by start time`() {
        val state = FamilienkalenderUiState(
            monthAnchor = LocalDate.of(2026, 6, 1),
            events = listOf(
                event("late", "2026-06-12", allDay = false, start = "18:00"),
                event("allday", "2026-06-12", allDay = true),
                event("early", "2026-06-12", allDay = false, start = "09:30"),
            ),
        )
        val ids = state.buckets["2026-06-12"]!!.events.map { it.id }
        assertEquals(listOf("allday", "early", "late"), ids)
    }

    @Test
    fun `meals sort by slot and kita is attached`() {
        val state = FamilienkalenderUiState(
            monthAnchor = LocalDate.of(2026, 6, 1),
            meals = listOf(
                meal("d", "2026-06-15", "DINNER"),
                meal("b", "2026-06-15", "BREAKFAST"),
            ),
            kitaClosures = listOf(KitaClosureDto(id = "k1", date = "2026-06-15", label = "Brückentag")),
        )
        val day = state.buckets["2026-06-15"]!!
        assertEquals(listOf("BREAKFAST", "DINNER"), day.meals.map { it.slot })
        assertNotNull(day.kita)
        assertEquals("Brückentag", day.kita!!.label)
        assertNull("an empty day has no bucket", state.buckets["2026-06-16"])
    }
}
