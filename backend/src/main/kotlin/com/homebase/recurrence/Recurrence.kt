package com.homebase.recurrence

import java.time.LocalDate

/**
 * The recurrence rules for todos (issue #44). Deliberately a tiny preset set — full RRULE would be
 * overkill for a household hub. All date math is "fixed schedule": the next instance is anchored to
 * the previous due date, not to the completion date, so "every Monday" / "the 1st" stays stable.
 */
object Recurrence {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"

    /** Valid frequencies on a stored/created recurrence. */
    val FREQUENCIES = setOf(DAILY, WEEKLY, MONTHLY)

    /** Sentinel a client may send on an update to remove a todo's recurrence. */
    const val CLEAR = "NONE"

    /** Upper bound on the every-N interval — a sane guard against absurd values. */
    const val MAX_INTERVAL = 1000

    // Defensive cap so a far-past anchor with a daily rule can never spin forever.
    private const val MAX_STEPS = 100_000

    /**
     * One step of the rule: the calendar date [interval] units after [from]. MONTHLY clamps to the
     * last valid day of the target month (java.time semantics: Jan 31 + 1 month = Feb 28/29).
     */
    fun nextOccurrence(from: LocalDate, freq: String, interval: Int): LocalDate {
        val n = interval.toLong().coerceAtLeast(1)
        return when (freq) {
            DAILY -> from.plusDays(n)
            WEEKLY -> from.plusWeeks(n)
            MONTHLY -> from.plusMonths(n)
            else -> throw IllegalArgumentException("unknown recurrence freq: $freq")
        }
    }

    /**
     * Due date for the instance that follows the one anchored at [from], guaranteed to be strictly
     * after [today]. For an on-time completion this is just one step on; completing a long-overdue
     * todo skips the periods that already elapsed so the successor still lands in the future.
     */
    fun nextDueAfterCompletion(from: LocalDate, freq: String, interval: Int, today: LocalDate): LocalDate {
        var next = nextOccurrence(from, freq, interval)
        var steps = 0
        while (!next.isAfter(today) && steps++ < MAX_STEPS) {
            next = nextOccurrence(next, freq, interval)
        }
        return next
    }

    /**
     * Safety-net for an *open, overdue* recurring todo: collapses the periods that have fully
     * elapsed by advancing [due] while the period after it has also already passed. Only whole
     * missed periods are skipped — the current period's occurrence is kept — so a chore stays on
     * schedule without ever piling up more than the single open instance. Returns [due] unchanged
     * when nothing has fully elapsed (e.g. only a day or two late).
     */
    fun rollOpenDueForward(due: LocalDate, freq: String, interval: Int, today: LocalDate): LocalDate {
        var d = due
        var steps = 0
        while (!nextOccurrence(d, freq, interval).isAfter(today) && steps++ < MAX_STEPS) {
            d = nextOccurrence(d, freq, interval)
        }
        return d
    }
}
