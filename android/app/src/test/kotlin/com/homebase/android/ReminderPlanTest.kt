package com.homebase.android

import com.homebase.android.notifications.ReminderInput
import com.homebase.android.notifications.ReminderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure tests for the Android local-reminder planner (#429 Phase 2c). No Android types involved —
 * mirrors the backend ReminderLogicTest intent (date-only skip, DONE skip, lead subtraction,
 * past-due-beyond-catchup skip).
 */
class ReminderPlanTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 6, 29)
    private val now: LocalDateTime = today.atTime(9, 0)

    private fun todo(
        id: String = "t1",
        title: String = "Zahnarzt",
        status: String = "PLANNED",
        dueDate: LocalDate? = today,
        dueTime: LocalTime? = LocalTime.of(14, 30),
        lead: Int? = null,
    ) = ReminderInput(id, title, status, dueDate, dueTime, lead)

    private fun fireInstant(r: com.homebase.android.notifications.ScheduledReminder): LocalDateTime =
        java.time.Instant.ofEpochMilli(r.fireAtEpochMillis).atZone(zone).toLocalDateTime()

    @Test
    fun `timed future todo is scheduled at the due moment`() {
        val plan = ReminderPlan.planReminders(listOf(todo()), now, zone)
        assertEquals(1, plan.size)
        val r = plan.single()
        assertEquals("t1", r.todoId)
        assertEquals("Zahnarzt", r.title)
        assertEquals("14:30", r.dueLabel)
        assertEquals(today.atTime(14, 30), fireInstant(r))
    }

    @Test
    fun `date-only todo (no dueTime) is skipped`() {
        val plan = ReminderPlan.planReminders(listOf(todo(dueTime = null)), now, zone)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `todo without a dueDate is skipped`() {
        val plan = ReminderPlan.planReminders(listOf(todo(dueDate = null)), now, zone)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `DONE todo is skipped even when timed`() {
        val plan = ReminderPlan.planReminders(listOf(todo(status = "DONE")), now, zone)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `lead minutes shift the fire moment earlier`() {
        // due 14:30, lead 90 -> fire 13:00
        val plan = ReminderPlan.planReminders(listOf(todo(lead = 90)), now, zone)
        val r = plan.single()
        assertEquals(today.atTime(13, 0), fireInstant(r))
        // label still shows the DUE time, not the fire time.
        assertEquals("14:30", r.dueLabel)
    }

    @Test
    fun `negative lead is clamped to zero`() {
        val plan = ReminderPlan.planReminders(listOf(todo(lead = -120)), now, zone)
        assertEquals(today.atTime(14, 30), fireInstant(plan.single()))
    }

    @Test
    fun `just-missed reminder within catchup still fires`() {
        // due 08:30 today, now 09:00 -> 30 min past, well within the 12h catch-up.
        val plan = ReminderPlan.planReminders(
            listOf(todo(dueTime = LocalTime.of(8, 30))), now, zone,
        )
        assertEquals(1, plan.size)
    }

    @Test
    fun `past-due beyond the catchup window is skipped`() {
        // due yesterday 14:30 -> ~18.5h ago at now, beyond the default 12h catch-up.
        val plan = ReminderPlan.planReminders(
            listOf(todo(dueDate = today.minusDays(1))), now, zone,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `catchup boundary is inclusive at exactly the window edge`() {
        // Fire moment exactly `catchup` before now must still fire (now - catchup is not "before").
        val catchup = Duration.ofHours(2)
        val fireAt = now.minus(catchup) // 07:00
        val plan = ReminderPlan.planReminders(
            listOf(todo(dueTime = fireAt.toLocalTime())), now, zone, catchup,
        )
        assertEquals(1, plan.size)
    }

    @Test
    fun `mixed list keeps only eligible todos`() {
        val plan = ReminderPlan.planReminders(
            listOf(
                todo(id = "future", dueTime = LocalTime.of(20, 0)),
                todo(id = "dateonly", dueTime = null),
                todo(id = "done", status = "DONE"),
                todo(id = "stale", dueDate = today.minusDays(2)),
            ),
            now, zone,
        )
        assertEquals(setOf("future"), plan.map { it.todoId }.toSet())
    }

    @Test
    fun `duplicate ids collapse to the last entry`() {
        val plan = ReminderPlan.planReminders(
            listOf(
                todo(id = "dup", title = "first", dueTime = LocalTime.of(15, 0)),
                todo(id = "dup", title = "second", dueTime = LocalTime.of(16, 0)),
            ),
            now, zone,
        )
        assertEquals(1, plan.size)
        assertEquals("second", plan.single().title)
        assertEquals("16:00", plan.single().dueLabel)
    }
}
