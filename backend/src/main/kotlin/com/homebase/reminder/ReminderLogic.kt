package com.homebase.reminder

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A todo eligible for a reminder. A todo opts in by carrying a due *time* — date-only todos don't
 * ping (#429 Phase 2a). The reminder fires at the due time minus the optional lead.
 */
data class ReminderCandidate(
    val title: String,
    val assignee: String?,
    val dueDate: LocalDate,
    val dueTime: LocalTime,
    val reminderLeadMinutes: Int?,
)

/**
 * Pure decision logic for the todo reminder scheduler (#429 Phase 2a). Side-effect-free so the
 * timing rules are unit-tested without a DB or wall clock; the [ReminderService] does the I/O.
 */
object ReminderLogic {
    /**
     * Past this much after the fire moment a still-unsent reminder is retired *without* sending —
     * so a first deploy or a long downtime doesn't dump a backlog of stale "this was due" pings.
     * Comfortably larger than a normal quiet-hours span so quiet-deferred reminders still fire.
     */
    val CATCHUP: Duration = Duration.ofHours(12)

    enum class Action { FIRE, RETIRE, WAIT }

    /** The local instant a candidate's reminder should fire: due moment minus the (non-negative) lead. */
    fun fireMoment(c: ReminderCandidate): LocalDateTime =
        c.dueDate.atTime(c.dueTime).minusMinutes((c.reminderLeadMinutes ?: 0).coerceAtLeast(0).toLong())

    /** What to do with a (still-unsent) candidate at [now]: not yet, fire now, or retire as stale. */
    fun decide(c: ReminderCandidate, now: LocalDateTime): Action {
        val fm = fireMoment(c)
        return when {
            now.isBefore(fm) -> Action.WAIT
            now.isBefore(fm.plus(CATCHUP)) -> Action.FIRE
            else -> Action.RETIRE
        }
    }

    /**
     * Is [now] inside the quiet-hours window [start, end)? Supports a window that wraps past
     * midnight (e.g. 22:00–07:00). A null/equal bound (incomplete config) means "no quiet hours".
     */
    fun inQuietHours(now: LocalTime, start: LocalTime?, end: LocalTime?): Boolean {
        if (start == null || end == null || start.compareTo(end) == 0) return false
        return if (start.isBefore(end)) !now.isBefore(start) && now.isBefore(end)
        else !now.isBefore(start) || now.isBefore(end) // wraps midnight
    }

    /** German reminder line, e.g. "🔔 Erinnerung: Zahnarzt — fällig 14:30 · bob". */
    fun message(c: ReminderCandidate): String {
        val time = "%02d:%02d".format(c.dueTime.hour, c.dueTime.minute)
        val who = c.assignee?.let { " · $it" } ?: ""
        return "🔔 Erinnerung: ${c.title} — fällig $time$who"
    }
}
