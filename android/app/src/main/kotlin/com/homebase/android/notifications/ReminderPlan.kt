package com.homebase.android.notifications

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Minimal, Android-free view of a todo for reminder planning (#429 Phase 2c). The Android layer
 * maps each [com.homebase.android.data.model.TodoDto] into one of these before planning, so the
 * timing rules below are pure and unit-testable without Robolectric or a wall clock.
 *
 * Mirrors the backend's [com.homebase.reminder.ReminderLogic] intent: a todo opts into a reminder
 * by carrying a due *time* — date-only todos never ping — and the reminder fires at the due moment
 * minus the optional, non-negative lead.
 */
data class ReminderInput(
    val id: String,
    val title: String,
    /** Backend status; only non-"DONE" todos remind. */
    val status: String,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val reminderLeadMinutes: Int?,
)

/**
 * A planned device-local notification: fire [fireAt] (already lead-adjusted, as an epoch-millis
 * instant in the device zone) and show "fällig [dueLabel]" for the todo [todoId].
 */
data class ScheduledReminder(
    val todoId: String,
    val title: String,
    /** "HH:mm" of the *due* time (not the fire time) — what the notification text shows. */
    val dueLabel: String,
    val fireAtEpochMillis: Long,
)

/**
 * Pure decision logic for the Android local-notification scheduler (#429 Phase 2c). Side-effect-free
 * so the timing rules are tested without WorkManager; [ReminderScheduler] does the I/O (enqueue /
 * cancel WorkManager jobs).
 */
object ReminderPlan {

    /**
     * Past this much after the fire moment a not-yet-fired reminder is dropped rather than scheduled —
     * so a fresh install, a re-login, or a long background gap doesn't dump a backlog of stale "this
     * was due" notifications. Mirrors the backend's catch-up window (12h).
     */
    val CATCHUP: Duration = Duration.ofHours(12)

    /** "HH:mm" (zero-padded, 24h) for a due time — what the notification text shows. */
    fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** The local instant a todo's reminder should fire: due moment minus the (non-negative) lead. */
    fun fireMoment(dueDate: LocalDate, dueTime: LocalTime, reminderLeadMinutes: Int?): LocalDateTime =
        dueDate.atTime(dueTime).minusMinutes((reminderLeadMinutes ?: 0).coerceAtLeast(0).toLong())

    /**
     * Plan the set of reminders to schedule for [todos] as of [now].
     *
     * A todo is included iff it
     *  - is not DONE,
     *  - has both a due date *and* a due time (date-only todos don't remind), and
     *  - its (lead-adjusted) fire moment is in the future, or at most [CATCHUP] in the past
     *    (a just-missed reminder still fires once; anything older is dropped, not backlogged).
     *
     * The returned list contains at most one entry per todo id (last-wins on duplicate ids, which
     * the API never produces). [zone] converts the local fire moment to an absolute epoch-millis the
     * WorkManager delay is computed from.
     */
    fun planReminders(
        todos: List<ReminderInput>,
        now: LocalDateTime,
        zone: ZoneId,
        catchup: Duration = CATCHUP,
    ): List<ScheduledReminder> {
        val byId = LinkedHashMap<String, ScheduledReminder>()
        for (t in todos) {
            if (t.status == "DONE") continue
            val date = t.dueDate ?: continue
            val time = t.dueTime ?: continue
            val fire = fireMoment(date, time, t.reminderLeadMinutes)
            // Future, or at most `catchup` in the past → schedule; older → skip.
            if (fire.isBefore(now.minus(catchup))) continue
            byId[t.id] = ScheduledReminder(
                todoId = t.id,
                title = t.title,
                dueLabel = hhmm(time),
                fireAtEpochMillis = fire.atZone(zone).toInstant().toEpochMilli(),
            )
        }
        return byId.values.toList()
    }
}
